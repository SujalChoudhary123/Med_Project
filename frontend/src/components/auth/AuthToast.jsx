import { useEffect } from "react";

function AuthToast({ message, onClose }) {
  useEffect(() => {
    if (!message) return undefined;
    const timer = setTimeout(() => onClose(), 4000);
    return () => clearTimeout(timer);
  }, [message, onClose]);

  if (!message) return null;
  return (
    <div
      role="status"
      style={{
        position: "fixed", top: "20px", right: "20px", zIndex: 9999,
        maxWidth: "min(420px, calc(100vw - 40px))", display: "flex",
        alignItems: "flex-start", gap: "12px", padding: "14px 16px",
        border: "1px solid #bbf7d0", borderRadius: "12px",
        background: "#f0fdf4", color: "#166534",
        boxShadow: "0 12px 30px rgba(15, 23, 42, .14)",
      }}
    >
      <span aria-hidden="true" style={{ fontSize: "20px" }}>✓</span>
      <div style={{ flex: 1, fontSize: "14px", lineHeight: 1.5 }}>{message}</div>
      <button type="button" onClick={onClose} aria-label="Dismiss notification" style={{ border: 0, background: "transparent", color: "inherit", cursor: "pointer", fontSize: "20px", lineHeight: 1 }}>×</button>
    </div>
  );
}

export default AuthToast;
