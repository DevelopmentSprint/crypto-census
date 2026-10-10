package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Keeps the public quickstart tied to the real Maven policy-override regression. */
class ReadmeQuickstartTest {
  @Test
  void readmePluginMatchesTheExecutableQuickstart() throws Exception {
    String readme = Files.readString(Path.of("..", "README.md"));
    String start = "<!-- quickstart-plugin:start -->";
    String end = "<!-- quickstart-plugin:end -->";
    assertThat(readme).containsOnlyOnce(start).containsOnlyOnce(end);
    String section = readme.substring(readme.indexOf(start) + start.length(), readme.indexOf(end));
    assertThat(section).contains("```xml");
    int codeStart = section.indexOf("```xml") + "```xml".length();
    Element documented = parse(section.substring(codeStart, section.indexOf("```", codeStart)));
    Element fixture = parse(Files.readString(Path.of("src/it/readme-quickstart/pom.xml")));
    var plugins = fixture.getElementsByTagName("plugin");
    Element executable = null;
    for (int i = 0; i < plugins.getLength(); i++) {
      Element plugin = (Element) plugins.item(i);
      if ("census-maven-plugin"
          .equals(plugin.getElementsByTagName("artifactId").item(0).getTextContent())) {
        executable = plugin;
      }
    }
    assertThat(executable).isNotNull();
    assertThat(documented.getTagName()).isEqualTo("plugin");
    var documentedVersions = documented.getElementsByTagName("version");
    assertThat(documentedVersions.getLength()).isEqualTo(1);
    assertThat(documentedVersions.item(0).getTextContent()).isNotBlank();
    // Invoker resolves the reactor version; docs can describe the release or the source snapshot.
    documentedVersions.item(0).setTextContent("@project.version@");
    assertThat(structure(documented)).isEqualTo(structure(executable));
  }

  private static Element parse(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory
        .newDocumentBuilder()
        .parse(new InputSource(new StringReader(xml)))
        .getDocumentElement();
  }

  private static String structure(Node node) {
    StringBuilder result = new StringBuilder();
    if (node.getNodeType() == Node.ELEMENT_NODE) {
      result.append('<').append(node.getNodeName()).append('>');
      for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
        result.append(structure(child));
      }
      result.append("</").append(node.getNodeName()).append('>');
    } else if (node.getNodeType() == Node.TEXT_NODE) {
      result.append(node.getTextContent().strip());
    }
    return result.toString();
  }
}
