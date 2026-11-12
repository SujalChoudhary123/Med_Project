// GST rate + HSN code inputs, shared by the Add and Edit medicine forms.
// Rendered as two grid cells so they sit in the form's existing 2-column layout.

const RATES = [0, 5, 12, 18, 28];

const labelStyle = {
  display: "block",
  marginBottom: "7px",
  fontSize: "14px",
  fontWeight: "600",
  color: "#374151",
};

const inputStyle = {
  width: "100%",
  boxSizing: "border-box",
  padding: "11px 13px",
  borderRadius: "8px",
  border: "1px solid #d1d5db",
  background: "#ffffff",
  color: "#111827",
  fontSize: "14px",
  outline: "none",
};

const hintStyle = {
  marginTop: "6px",
  fontSize: "12px",
  color: "#6b7280",
};

function GstFields({ gstRate, hsn, costPrice, costHint, onChange }) {
  return (
    <>
      <div>
        <label style={labelStyle} htmlFor="gstRate">
          GST Rate
        </label>

        <select
          id="gstRate"
          name="gstRate"
          value={String(gstRate)}
          onChange={onChange}
          style={inputStyle}
        >
          {RATES.map((rate) => (
            <option key={rate} value={String(rate)}>
              {rate}%
            </option>
          ))}
        </select>

        <div style={hintStyle}>Use the rate your accountant gives for this medicine.</div>
      </div>

      <div>
        <label style={labelStyle} htmlFor="hsn">
          HSN Code
        </label>

        <input
          id="hsn"
          name="hsn"
          value={hsn}
          onChange={onChange}
          placeholder="3004"
          maxLength={8}
          inputMode="numeric"
          style={inputStyle}
        />

        <div style={hintStyle}>4 to 8 digits. Medicines are usually 3004.</div>
      </div>

      <div>
        <label style={labelStyle} htmlFor="costPrice">
          Cost Price (optional)
        </label>

        <input
          id="costPrice"
          name="costPrice"
          type="number"
          min="0"
          step="0.01"
          value={costPrice}
          onChange={onChange}
          placeholder="Rate before GST"
          inputMode="decimal"
          style={inputStyle}
        />

        <div style={hintStyle}>{costHint || "Needed for profit reports. Cashiers never see it."}</div>
      </div>
    </>
  );
}

export default GstFields;
