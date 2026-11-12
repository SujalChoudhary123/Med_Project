

function BulkResultBox({
  label,
  value,
  success = false,
  danger = false,
}) {

  return (

    <div
      style={{
        padding:
          "10px 12px",

        borderRadius:
          "8px",

        background:
          danger
            ? "#fef2f2"
            : success
              ? "#ecfdf5"
              : "#ffffff",

        border:
          danger
            ? "1px solid #fecaca"
            : success
              ? "1px solid #a7f3d0"
              : "1px solid #e5e7eb",

        textAlign:
          "center",
      }}
    >

      <div
        style={{
          fontSize:
            "11px",

          color:
            "#6b7280",

          marginBottom:
            "3px",
        }}
      >
        {label}
      </div>


      <div
        style={{
          fontSize:
            "18px",

          fontWeight:
            "700",

          color:
            danger
              ? "#b91c1c"
              : success
                ? "#047857"
                : "#111827",
        }}
      >
        {value}
      </div>

    </div>
  );
}

export default BulkResultBox;
