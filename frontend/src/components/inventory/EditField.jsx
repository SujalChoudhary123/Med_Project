

function EditField({
  label,
  name,
  type = "text",
  value,
  onChange,
  disabled = false,
  min,
  step,
}) {

  return (

    <div>

      <label
        style={{
          display:
            "block",
          marginBottom:
            "7px",
          fontSize:
            "14px",
          fontWeight:
            "600",
          color:
            "#374151",
        }}
      >
        {label}
      </label>


      <input
        type={type}
        name={name}
        value={value}
        onChange={
          onChange
        }
        disabled={
          disabled
        }
        min={min}
        step={step}
        style={{
          width:
            "100%",
          boxSizing:
            "border-box",
          padding:
            "11px 13px",
          borderRadius:
            "8px",
          border:
            "1px solid #d1d5db",
          background:
            disabled
              ? "#f3f4f6"
              : "#ffffff",
          color:
            "#111827",
          fontSize:
            "14px",
          outline:
            "none",
        }}
      />

    </div>

  );
}


/* =========================================================
   ADD MEDICINE
========================================================= */

export default EditField;
