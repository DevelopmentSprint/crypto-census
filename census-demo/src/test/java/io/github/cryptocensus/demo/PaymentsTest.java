package io.github.cryptocensus.demo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentsTest {

    @Test
    void signsAndVerifiesInstructions() throws Exception {
        PaymentSigner signer = new PaymentSigner();
        byte[] sig = signer.sign("pay 100 EUR to NL91ABNA0417164300");
        assertThat(signer.verify("pay 100 EUR to NL91ABNA0417164300", sig)).isTrue();
    }

    @Test
    void sealsCardNumbers() throws Exception {
        CardVault vault = new CardVault();
        assertThat(vault.open(vault.seal("4111111111111111"))).isEqualTo("4111111111111111");
    }

    @Test
    void exportsStatementsWithChecksum() throws Exception {
        assertThat(new StatementExport().checksum("statement")).hasSize(40);
    }
}
