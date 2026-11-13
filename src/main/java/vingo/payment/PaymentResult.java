package vingo.payment;

public record PaymentResult(String rspCode, String message) {

    public static PaymentResult of(String rspCode, String message) {
        return new PaymentResult(rspCode, message);
    }
}
