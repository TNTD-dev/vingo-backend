package vingo.payment;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import vingo.payment.internal.VNPaySigner;

class VNPaySignerTest {

    private final VNPaySigner signer = new VNPaySigner(PaymentTestSupport.TEST_SECRET);

    @Test
    void signParams_producesDeterministicHmacSha512OverSortedEncodedParams() {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "8F3K2Q-1");
        params.put("vnp_Amount", "50000000");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", "TESTTMN1");
        params.put("vnp_OrderInfo", "Thanh toan don 8F3K2Q-1");
        params.put("vnp_SecureHash", "ignored-existing-hash");

        String actual = signer.signParams(params);
        String expected = PaymentTestSupport.signForTest(params);

        assertThat(actual).hasSize(128).isEqualToIgnoringCase(expected);
    }

    @Test
    void verifyParams_returnsTrue_whenSignatureMatchesRegardlessOfCase() {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "8F3K2Q-1");
        params.put("vnp_Amount", "50000000");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionStatus", "00");
        String hash = PaymentTestSupport.signForTest(params);

        params.put("vnp_SecureHash", hash.toUpperCase(Locale.ROOT));
        assertThat(signer.verifyParams(params)).isTrue();

        params.put("vnp_SecureHash", hash.toLowerCase(Locale.ROOT));
        assertThat(signer.verifyParams(params)).isTrue();
    }

    @Test
    void verifyParams_returnsFalse_whenParameterTamperedOrHashMissing() {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "8F3K2Q-1");
        params.put("vnp_Amount", "50000000");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_SecureHash", PaymentTestSupport.signForTest(params));

        params.put("vnp_Amount", "10000");
        assertThat(signer.verifyParams(params)).isFalse();

        params.remove("vnp_SecureHash");
        assertThat(signer.verifyParams(params)).isFalse();
    }
}