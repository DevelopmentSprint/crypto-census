#!/usr/bin/env python3
"""Optional local synthetic measurements. No network, service, or production configuration changes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
MAIN = "com.developmentsprint.cryptocensus.it.EnrichmentMeasurementWorkload"
MODES = ("off", "core", "enrichment", "tls")


def command(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs).stdout


def generate(output, annotations, java_home, count, batch=0, annotated=True):
    source = output / "sources"
    classes = output / "classes"
    source.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    files = []
    for index in range(count):
        methods = []
        for method in range(32):
            annotation = (f'@com.developmentsprint.cryptocensus.annotations.CryptoFlow('
                          f'"generated.{batch}.{index}.{method}")\n') if annotated else ""
            calls = "".join(f'java.security.MessageDigest.getInstance("{algorithm}");'
                            for algorithm in ("SHA-256", "SHA-384", "SHA-512", "SHA-1", "MD5"))
            methods.append(f'{annotation}public static void m{method}() throws Exception {{{calls}}}')
        file = source / f"Generated{index}.java"
        file.write_text("package measurement; public final class Generated" + str(index) + "{\n"
                        + "\n".join(methods) + "\n}\n")
        files.append(file)
    command([java_home / "bin/javac", "--release", "17", "-cp", annotations,
             "-d", classes, *files], timeout=120)
    (classes / "count.txt").write_text(str(count))
    (classes / "name.txt").write_text("measurement.Generated0")
    return classes


def run(java_home, agent, annotations, out, mode, workload, fixture, iterations, rounds,
        fork, diagnostics=False, extra_classpath=None):
    label = f"{workload}-{mode}-{fork}" + ("-diagnostic" if diagnostics else "")
    directory = out / label
    directory.mkdir(parents=True, exist_ok=False)
    classpath = [ROOT / "census-agent/target/test-classes", annotations]
    if extra_classpath:
        classpath.append(extra_classpath)
    args = [java_home / "bin/java", "-Xms256m", "-Xmx256m"]
    if diagnostics:
        dump = directory / "bytecode"
        dump.mkdir()
        args += [f"-Dcom.developmentsprint.cryptocensus.agent.shaded.bytebuddy.dump={dump}", "-XX:+UnlockDiagnosticVMOptions",
                 "-XX:+LogCompilation", f"-XX:LogFile={directory / 'jit.xml'}"]
    if mode != "off":
        options = [f"output={quote(str(directory / 'cbom.json'), safe='/')}", "summary=false", "maxSites=8"]
        if mode in ("enrichment", "tls"):
            options += ["enrichment=true", "applicationId=measurement", "maxScopeDepth=8"]
        if mode == "tls":
            options += ["tlsCorrelation=true"]
        args.append(f"-javaagent:{agent}=" + ",".join(options))
    args += ["-cp", os.pathsep.join(map(str, classpath)), MAIN, workload,
             str(iterations), str(rounds), fixture]
    start = time.monotonic_ns()
    # stdout is a pipe so READY gives spawn-to-main time; stderr stays separate.
    with (directory / "stderr.txt").open("w") as errors:
        process = subprocess.Popen(list(map(str, args)), stdout=subprocess.PIPE,
                                   stderr=errors, text=True)
        lines = []
        ready = None
        try:
            # READY precedes workloads. A parent watchdog bounds even pre-main failure.
            import threading
            timer = threading.Timer(180, process.kill)
            timer.start()
            for line in process.stdout:
                lines.append(line)
                if line.strip() == "READY":
                    ready = time.monotonic_ns() - start
            status = process.wait(timeout=10)
        finally:
            timer.cancel()
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()
    elapsed = time.monotonic_ns() - start
    text = "".join(lines)
    (directory / "stdout.txt").write_text(text)
    (directory / "command.json").write_text(json.dumps(list(map(str, args)), indent=2))
    if status != 0 or ready is None:
        raise RuntimeError(f"{label} failed ({status}); inspect {directory}")
    samples = []
    for line in lines:
        if line.startswith("SAMPLE "):
            _, round_number, count, nanos, allocated = line.split()
            samples.append(dict(round=int(round_number), operations=int(count), nanos=int(nanos),
                                allocated_bytes=int(allocated)))
    if workload != "stress" and len(samples) != rounds:
        raise RuntimeError(f"{label}: missing measurement samples")
    qualification = qualify(directory, mode, workload, iterations, rounds)
    return dict(qualification=qualification, mode=mode, workload=workload, fork=fork, startup_nanos=ready,
                process_nanos=elapsed, samples=samples, diagnostic=diagnostics,
                registry=[line.strip() for line in lines if line.startswith("REGISTRY ")],
                memory=[line.strip() for line in lines if line.startswith("MEMORY ")],
                evidence_written=(directory / "cbom.evidence.json").exists())


def qualify(directory, mode, workload, iterations, rounds):
    """Reject deceptively fast disabled/degraded runs; stress loss is a separate result."""
    if workload.startswith(("prefilter-", "matching-")):
        expected = 32 * iterations * (rounds + 1) if workload.endswith("annotated") else 0
        if f"CHECKSUM {expected}\n" not in (directory / "stdout.txt").read_text():
            raise ValueError("Descriptor/name-matcher did not return the expected declaration count")
    if mode == "off":
        return "no agent"
    cbom = directory / "cbom.json"
    if not cbom.is_file():
        raise ValueError(f"Missing core CBOM: {directory}")
    if mode == "core":
        return "core CBOM present"
    evidence = directory / "cbom.evidence.json"
    if workload == "stress" and not evidence.is_file():
        log = (directory / "stdout.txt").read_text()
        if "REGISTRY 3 4096 16384 " not in log:
            raise ValueError("Stress failed to reach and retain both configured bounds")
        return "bounded live registries; sidecar publication failed (not a complete evidence run)"
    document = json.loads(evidence.read_text())
    if document["cbomHash"] != hashlib.sha256(cbom.read_bytes()).hexdigest():
        raise ValueError("Evidence does not bind to exact CBOM")
    capabilities = {item["feature"]: item["status"] for item in document["capabilities"]}
    if capabilities.get("annotation-scopes") != "SUPPORTED":
        raise ValueError("Annotation instrumentation not supported in measured run")
    if workload != "stress" and any(document["health"].values()):
        raise ValueError(f"Degraded measured evidence: {document['health']}")
    subjects = {flow["definition"]["subject"]["flowId"] for flow in document["flows"]}
    if not {"measurement.hot", "measurement.digest", "measurement.tls"}.issubset(subjects):
        raise ValueError("Missing annotation declarations in measured run")
    if workload == "scoped-digest":
        count = sum(item["count"] for item in document["observations"]
                    if any(ref["subject"]["flowId"] == "measurement.digest"
                           for ref in item["context"]["path"]))
        if count != iterations * (rounds + 1):
            raise ValueError(f"Scoped digest count {count} does not match actual invocations")
    if workload == "tls" and mode == "tls":
        handshakes = document["handshakes"]
        if (capabilities.get("tls-correlation") != "SUPPORTED"
                or len(handshakes) != 8 * iterations * (rounds + 1)
                or any(item["outcome"] != "COMPLETED" for item in handshakes)
                or {item["transport"] for item in handshakes} != {"SOCKET", "ENGINE"}
                or not any(item["resumption"]["resumed"] == "YES" for item in handshakes)
                or not any(item["initiation"]["path"] for item in handshakes)):
            raise ValueError("Measured TLS batch lacks completed, resumed, engine or scoped evidence")
    return "exact-byte paired, healthy evidence; workload-specific checks passed"


def summarize(results):
    rows = []
    keys = sorted({(item["workload"], item["mode"]) for item in results if not item["diagnostic"]})
    for workload, mode in keys:
        group = [item for item in results if item["workload"] == workload and item["mode"] == mode
                 and not item["diagnostic"]]
        timings = [sample["nanos"] / sample["operations"] for item in group for sample in item["samples"]]
        allocations = [sample["allocated_bytes"] / sample["operations"] for item in group
                       for sample in item["samples"] if sample["allocated_bytes"] >= 0]
        rows.append(dict(workload=workload, mode=mode,
                         startup_ms=statistics.median(item["startup_nanos"] for item in group) / 1e6,
                         ns_per_operation=statistics.median(timings) if timings else None,
                         min_ns=min(timings) if timings else None, max_ns=max(timings) if timings else None,
                         allocated_bytes_per_operation=statistics.median(allocations) if allocations else None))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "")))
    parser.add_argument("--output", type=Path, required=True, help="New directory inside this checkout's target")
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--skip-stress", action="store_true")
    parser.add_argument("--skip-tls", action="store_true")
    args = parser.parse_args()
    out = args.output.resolve()
    if not out.is_relative_to(ROOT / "target") or out.exists():
        parser.error("Output must be a new directory under this checkout's target")
    if args.forks < 1 or args.rounds < 1 or not (args.java_home / "bin/java").is_file():
        parser.error("Require positive forks/rounds and a valid --java-home")
    for variable in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        if os.environ.get(variable):
            parser.error(f"Unset {variable} for an uncontaminated child JVM configuration")
    out.mkdir(parents=True)
    agent = ROOT / "census-agent/target/census-agent-0.1.0.jar"
    annotations = ROOT / "census-annotations/target/classes"
    if not agent.is_file():
        parser.error("Build first: ./mvnw -pl census-agent -am package -DskipTests -Dgitbuildhook.gitconfig.skip=true")
    generated = {kind: generate(out / kind, annotations, args.java_home, 8,
                                annotated=kind == "annotated") for kind in ("plain", "annotated")}
    workloads = [("plain-hot", 2_000_000), ("scoped-hot", 2_000_000),
                 ("plain-digest", 10_000), ("scoped-digest", 10_000), ("stackwalk", 10_000),
                 ("prefilter-plain", 10_000), ("prefilter-annotated", 10_000),
                 ("matching-plain", 10_000), ("matching-annotated", 10_000),
                 ("load-plain", 1), ("load-annotated", 1)]
    store = out / "measurement.p12"
    if not args.skip_tls:
        command([args.java_home / "bin/keytool", "-genkeypair", "-alias", "measurement", "-keyalg", "RSA",
                 "-keysize", "2048", "-storetype", "PKCS12", "-keystore", store,
                 "-storepass", "measurement", "-keypass", "measurement", "-dname", "CN=localhost",
                 "-validity", "2", "-noprompt"], timeout=30)
        workloads.append(("tls", 1))
    results = []
    for fork in range(args.forks):
        modes = MODES[fork % len(MODES):] + MODES[:fork % len(MODES)]
        for workload, iterations in workloads:
            for mode in modes:
                if mode == "tls" and workload != "tls":
                    continue
                if mode == "off" and workload.startswith(("stackwalk", "prefilter", "matching")):
                    continue
                fixture = out
                extra = None
                if workload.startswith(("load-", "prefilter-", "matching-")):
                    fixture = generated[workload.split("-")[1]]
                    if workload.startswith("prefilter"):
                        fixture /= "measurement/Generated0.class"
                    elif workload.startswith("matching"):
                        extra = fixture
                        fixture /= "name.txt"
                elif workload == "tls":
                    fixture = store
                print(f"{fork + 1}/{args.forks}: {workload} {mode}", flush=True)
                results.append(run(args.java_home, agent, annotations, out, mode, workload,
                                   fixture, iterations, args.rounds, fork, extra_classpath=extra))
                (out / "results.json").write_text(json.dumps(results, indent=2))
    for mode in ("off", "core", "enrichment"):
        results.append(run(args.java_home, agent, annotations, out, mode, "scoped-hot", out,
                           2_000_000, 1, 0, diagnostics=True))
    if not args.skip_stress:
        stress = out / "stress-input"
        for batch in range(3):
            classes = generate(stress / f"build-{batch}", annotations, args.java_home, 150, batch=batch)
            classes.rename(stress / str(batch))
        results.append(run(args.java_home, agent, annotations, out, "enrichment", "stress",
                           stress, 3, 0, 0))
    diagnostics = json.loads(command([sys.executable, ROOT / "scripts/summarize-enrichment-diagnostics.py", out]))
    (out / "diagnostics.json").write_text(json.dumps(diagnostics, indent=2) + "\n")
    metadata = dict(java=command([args.java_home / "bin/java", "-version"]).strip(),
                    architecture=platform.machine(), os=platform.system(), os_release=platform.release(),
                    logical_cpus=os.cpu_count(),
                    revision=command(["git", "rev-parse", "HEAD"], cwd=ROOT).strip(),
                    agent_sha256=hashlib.sha256(agent.read_bytes()).hexdigest(), forks=args.forks,
                    rounds=args.rounds, heap="256 MiB fixed", results=results, summary=summarize(results))
    (out / "measurements.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(out / "measurements.json")


if __name__ == "__main__":
    main()
