

function LogoutConfirmModal({ onCancel, onConfirm }) {
  return (
    <div role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onCancel(); }} style={{ position: "fixed", inset: 0, zIndex: 10000, display: "flex", alignItems: "center", justifyContent: "center", padding: "20px", background: "rgba(15, 23, 42, .48)" }}>
      <div role="dialog" aria-modal="true" aria-labelledby="logout-confirm-title" style={{ width: "100%", maxWidth: "400px", padding: "26px", borderRadius: "16px", background: "#fff", boxShadow: "0 24px 60px rgba(15, 23, 42, .25)" }}>
        <div style={{ width: "46px", height: "46px", display: "grid", placeItems: "center", borderRadius: "12px", background: "#fff7ed", color: "#c2410c", fontSize: "22px", marginBottom: "16px" }}>↪</div>
        <h2 id="logout-confirm-title" style={{ margin: "0 0 8px", color: "#111827", fontSize: "20px" }}>Logout from MedTrack?</h2>
        <p style={{ margin: "0 0 24px", color: "#6b7280", fontSize: "14px", lineHeight: 1.6 }}>Are you sure you want to log out of your account?</p>
        <div style={{ display: "flex", justifyContent: "flex-end", gap: "10px" }}>
          <button type="button" onClick={onCancel} style={{ padding: "10px 16px", border: "1px solid #d1d5db", borderRadius: "8px", background: "#fff", color: "#374151", fontWeight: 600, cursor: "pointer" }}>Cancel</button>
          <button type="button" onClick={onConfirm} style={{ padding: "10px 16px", border: 0, borderRadius: "8px", background: "#dc2626", color: "#fff", fontWeight: 600, cursor: "pointer" }}>Logout</button>
        </div>
      </div>
    </div>
  );
}

export default LogoutConfirmModal;
