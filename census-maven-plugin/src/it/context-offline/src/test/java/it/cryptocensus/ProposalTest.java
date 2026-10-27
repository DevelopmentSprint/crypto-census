package it.cryptocensus;

import com.developmentsprint.cryptocensus.assessment.*;
import com.developmentsprint.cryptocensus.core.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProposalTest {
    @Test void exportValidatedProposal() throws Exception {
        if (java.nio.file.Files.exists(Path.of("target/test-ran"))) {
            throw new AssertionError("Offline assessment reran application tests");
        }
        java.nio.file.Files.writeString(Path.of("target/test-ran"), "once");
        Cbom.write(new Inventory(), new Cbom.Metadata("offline", java.util.Map.of()), Path.of("target/saved/cbom-one.json"));
        java.nio.file.Files.copy(Path.of("target/saved/cbom-one.json"), Path.of("target/manual-output"));
        var flow = new ContextDocument.Flow(new ContextModel.Subject("billing", "archive"),
            "Records archive", ContextDocument.State.ACTIVE, List.of());
        var proposed = new ContextDocument(1, List.of(flow), List.of(), List.of(), List.of());
        var changes = ContextChanges.between(ContextDocument.empty(), proposed);
        DocumentFiles.write(Path.of("target/proposal.json"), ContextChanges.encode(changes));
    }
}
