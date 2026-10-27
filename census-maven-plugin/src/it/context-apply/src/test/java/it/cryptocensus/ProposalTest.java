package it.cryptocensus;

import com.developmentsprint.cryptocensus.assessment.*;
import com.developmentsprint.cryptocensus.core.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProposalTest {
    @Test void exportValidatedProposal() throws Exception {
        var flow = new ContextDocument.Flow(new ContextModel.Subject("billing", "archive"),
            "Records archive", ContextDocument.State.ACTIVE, List.of());
        var proposed = new ContextDocument(1, List.of(flow), List.of(), List.of(), List.of());
        var changes = ContextChanges.between(ContextDocument.empty(), proposed);
        DocumentFiles.write(Path.of("target/proposal.json"), ContextChanges.encode(changes));
    }
}
