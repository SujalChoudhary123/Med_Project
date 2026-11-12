import { useState } from "react";
import { createPortal } from "react-dom";
import { api } from "../../config.js";
import {
  PAYMENT_MODES,
  computeLine,
  inr,
} from "../../utils/invoiceUtils.js";

const REASONS = [
  "Customer changed mind",
  "Wrong medicine given",
  "Doctor changed prescription",
  "Damaged or defective",
  "Expired / near expiry",
  "Other",
];

// Medicines that should not go back on the shelf.
const NO_RESTOCK = new Set(["Damaged or defective", "Expired / near expiry"]);

/**
 * Choose which medicines of a bill come back, how the customer is refunded,
 * and whether the stock goes back on the shelf. The server works out the
 * refund; the figure shown here is only a preview.
 */
function ReturnDialog({ invoice, onClose, onDone }) {
  const returnable = invoice.items.filter(
    (item) => item.quantity - item.returnedQuantity > 0
  );

  const [quantities, setQuantities] = useState({});
  const [reasonChoice, setReasonChoice] = useState(REASONS[0]);
  const [otherReason, setOtherReason] = useState("");
  const [refundMode, setRefundMode] = useState(
    invoice.paymentMode === "credit" ? "credit" : invoice.paymentMode
  );
  const isCredit = invoice.paymentMode === "credit";
  const [restock, setRestock] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const chosen = returnable.filter((item) => (quantities[item.itemId] || 0) > 0);

  // Preview only; the server calculates the real refund.
  const preview = Math.round(
    chosen.reduce(
      (sum, item) =>
        sum +
        computeLine({
          price: item.unitPrice,
          quantity: quantities[item.itemId],
          discountPercent: item.discountPercent,
          gstRate: item.gstRate,
          inclusive: invoice.pricesIncludeGst,
        }).lineTotal,
      0
    )
  );

  const changeReason = (value) => {
    setReasonChoice(value);
    setRestock(!NO_RESTOCK.has(value));
  };

  const setQuantity = (item, value) => {
    const left = item.quantity - item.returnedQuantity;
    const number = Math.min(left, Math.max(0, parseInt(value, 10) || 0));
    setQuantities((current) => ({ ...current, [item.itemId]: number }));
  };

  const submit = async (event) => {
    event.preventDefault();
    setError("");

    if (chosen.length === 0) {
      setError("Enter the quantity to return for at least one medicine.");
      return;
    }

    const reason = reasonChoice === "Other" ? otherReason.trim() : reasonChoice;

    if (!reason) {
      setError("Please write the reason for the return.");
      return;
    }

    try {
      setSaving(true);

      const response = await fetch(api(`/api/invoices/${invoice.id}/return`), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          reason,
          refundMode,
          restock,
          items: chosen.map((item) => ({
            itemId: item.itemId,
            quantity: quantities[item.itemId],
          })),
        }),
      });
      const data = await response.json().catch(() => ({}));

      if (!response.ok || data.success === false) {
        throw new Error(data.error || "The return could not be saved.");
      }

      onDone(data.creditNote);
    } catch (problem) {
      setError(problem.message);
    } finally {
      setSaving(false);
    }
  };

  return createPortal(
    <div className="return-overlay no-print" role="dialog" aria-modal="true" aria-label="Return items">
      <form className="return-card" onSubmit={submit}>
        <div className="return-head">
          <div>
            <h2>Return items</h2>
            <p>
              Invoice {invoice.invoiceNo} · {invoice.customer.name}
            </p>
          </div>
          <button type="button" className="return-close" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="return-body">
          <table className="bill-table return-table cards">
            <thead>
              <tr>
                <th className="left">Medicine</th>
                <th>Bought</th>
                <th>Returnable</th>
                <th>Return qty</th>
              </tr>
            </thead>
            <tbody>
              {returnable.map((item) => {
                const left = item.quantity - item.returnedQuantity;
                return (
                  <tr key={item.itemId}>
                    <td className="left lead">
                      <strong>{item.name}</strong>
                      <small>
                        {inr(item.unitPrice)}
                        {item.discountPercent ? ` · ${item.discountPercent}% off` : ""} · GST {item.gstRate}%
                      </small>
                    </td>
                    <td data-label="Bought">{item.quantity}</td>
                    <td data-label="Returnable">{left}</td>
                    <td data-label="Return qty">
                      <input
                        type="number"
                        min="0"
                        max={left}
                        value={quantities[item.itemId] || 0}
                        aria-label={`Return quantity of ${item.name}`}
                        onChange={(event) => setQuantity(item, event.target.value)}
                      />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          <div className="return-fields">
            <label>
              Reason
              <select value={reasonChoice} onChange={(event) => changeReason(event.target.value)}>
                {REASONS.map((reason) => (
                  <option key={reason} value={reason}>
                    {reason}
                  </option>
                ))}
              </select>
            </label>

            {reasonChoice === "Other" && (
              <label>
                Write the reason
                <input
                  value={otherReason}
                  maxLength={200}
                  placeholder="Why is it being returned?"
                  onChange={(event) => setOtherReason(event.target.value)}
                />
              </label>
            )}

            <label>
              Refund by
              <select
                value={isCredit ? "credit" : refundMode}
                disabled={isCredit}
                onChange={(event) => setRefundMode(event.target.value)}
              >
                {PAYMENT_MODES.map((mode) => (
                  <option key={mode.value} value={mode.value}>
                    {mode.value === "credit" ? "Credit / adjust against account" : mode.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {isCredit && (
            <p className="return-hint">
              This was an udhaar bill, so the refund is taken off what the customer owes
              instead of being paid out.
            </p>
          )}

          <label className="return-check">
            <input
              type="checkbox"
              checked={restock}
              onChange={(event) => setRestock(event.target.checked)}
            />
            <span>
              <strong>Put the medicines back in stock</strong>
              <small>
                Turn this off for damaged or expired medicines, so they are not sold again.
              </small>
            </span>
          </label>

          {error && <div className="bill-message error" role="alert">{error}</div>}
        </div>

        <div className="return-foot">
          <div>
            <span>Refund (approx.)</span>
            <strong>{inr(preview)}</strong>
          </div>
          <div className="return-buttons">
            <button type="button" className="secondary-button" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="primary-button" disabled={saving || chosen.length === 0}>
              {saving ? "Saving..." : "Create credit note"}
            </button>
          </div>
        </div>
      </form>
    </div>,
    document.body
  );
}

export default ReturnDialog;
