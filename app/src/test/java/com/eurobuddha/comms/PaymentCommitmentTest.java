package com.eurobuddha.comms;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Security review H1: the order carries a secret; the payment carries its commitment in state port 2. */
public class PaymentCommitmentTest {
    @Test public void thePaymentCarriesTheCommitmentOfTheOrdersSecret() {
        String nonce = MerchMessage.newNonce(), c = MerchMessage.commitment(nonce);
        assertTrue(MerchMessage.validNonce(nonce));
        assertTrue(c, c.matches("0x[0-9A-F]{64}"));
        assertNotEquals(c, MerchMessage.commitment(MerchMessage.newNonce()));
        NodeApi node = mock(NodeApi.class);
        CommsTransport.sendPayment(node, "0x" + "12".repeat(32), "10", "0x00", "ORD-0123456789ABCDEF", c, mock(CommsTransport.SendCb.class));
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(node).cmd(cmd.capture(), any());
        assertTrue(cmd.getValue(), cmd.getValue().contains("\"2\":\"" + c + "\""));
        CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class);
        CommsTransport.sendPayment(mock(NodeApi.class), "0x" + "12".repeat(32), "10", "0x00", "ORD-1", "0x12;send", cb);
        verify(cb).onFailed(anyString());
    }

    @Test public void theSecretTravelsInsideTheSealedOrder() {
        MerchMessage m = new MerchMessage(); m.type = MerchMessage.ORDER; m.ref = "ORD-0123456789ABCDEF"; m.nonce = MerchMessage.newNonce();
        assertEquals(m.nonce, MerchMessage.fromWire(m.toWire()).nonce);
        assertTrue(MerchMessage.validRef(m.ref));
    }
}
