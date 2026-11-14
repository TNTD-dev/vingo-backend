UPDATE orders o
SET buyer_name = bp.full_name,
    buyer_email = bp.email,
    buyer_phone = bp.phone
FROM buyer_profile bp
WHERE bp.user_id = o.user_id
  AND o.buyer_email IS NULL
  AND o.status IN ('PAID', 'NEEDS_REVIEW', 'REFUNDED');
