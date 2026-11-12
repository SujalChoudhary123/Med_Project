

function FormField({
  label,
  name,
  type = "text",
  value,
  onChange,
  placeholder,
  required,
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

        {required && (

          <span
            style={{
              color:
                "#dc2626",
              marginLeft:
                "3px",
            }}
          >
            *
          </span>

        )}

      </label>


      <input
        type={type}
        name={name}
        value={value}
        onChange={
          onChange
        }
        placeholder={
          placeholder
        }
        required={
          required
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
            "#ffffff",
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
   SALES & BILLING
========================================================= */

export default FormField;
