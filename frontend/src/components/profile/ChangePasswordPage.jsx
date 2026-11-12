import { useState } from "react";
import { api } from "../../config.js";

const API_BASE = api("/api");
const styles = `
.password-settings-page{flex:1;min-height:0;overflow-y:auto;padding:26px 30px 40px;color:#172033}
.password-settings-wrap{max-width:1080px;margin:0 auto}
.password-eyebrow{font-size:11px;font-weight:800;letter-spacing:1.8px;color:#059669;margin:0 0 9px}
.password-title{font-size:30px;line-height:1.2;margin:0;color:#111827;font-weight:750}
.password-subtitle{margin:9px 0 26px;color:#718096;font-size:14px}
.password-card{background:#fff;border:1px solid #e4e9ef;border-radius:20px;box-shadow:0 8px 28px #0f172a08;overflow:hidden}
.password-card-head{padding:22px 26px;border-bottom:1px solid #edf0f4;display:flex;align-items:center;gap:14px}
.password-icon{height:46px;width:46px;border-radius:14px;display:grid;place-items:center;background:#e8fbf3;color:#059669;font-size:22px;flex-shrink:0}
.password-card-head h3{margin:0 0 4px;font-size:17px;color:#182230}.password-card-head p{margin:0;color:#7b8797;font-size:13px}
.password-card-body{padding:25px 26px 28px}.password-form-grid{display:grid;grid-template-columns:1fr;gap:18px;max-width:620px}
.password-label{display:block;margin-bottom:8px;color:#374151;font-size:13px;font-weight:750}
.password-input-wrap{position:relative}.password-input{width:100%;box-sizing:border-box;padding:13px 78px 13px 14px;border:1px solid #d7dee7;border-radius:10px;background:#fff;color:#172033;font-size:14px;outline:none;transition:border-color .15s,box-shadow .15s}.password-input:focus{border-color:#10a779;box-shadow:0 0 0 3px #10a77918}
.password-toggle{position:absolute;right:9px;top:50%;transform:translateY(-50%);border:0;background:transparent;color:#64748b;font-size:12px;font-weight:750;cursor:pointer;padding:6px}
.password-hint{font-size:12px;color:#8a96a5;margin-top:7px}.password-rules{display:flex;gap:8px;flex-wrap:wrap;margin:18px 0 24px}.password-rule{font-size:11px;color:#637083;background:#f4f7f9;border:1px solid #e7edf2;border-radius:999px;padding:6px 10px}.password-rule.valid{color:#047857;background:#ecfdf5;border-color:#a7f3d0}
.password-actions{display:flex;gap:10px;flex-wrap:wrap}.password-btn{border:0;border-radius:10px;padding:12px 17px;font-size:13px;font-weight:750;cursor:pointer;transition:transform .15s,background .15s}.password-btn:hover{transform:translateY(-1px)}.password-btn:disabled{opacity:.6;cursor:wait;transform:none}.password-primary{background:#059669;color:#fff}.password-primary:hover{background:#047857}.password-outline{background:#fff;border:1px solid #d9e1e9;color:#3b4758}
.password-message{padding:13px 15px;border-radius:12px;margin-bottom:20px;font-size:13px;line-height:1.5}.password-message.error{background:#fff1f2;border:1px solid #fecdd3;color:#be123c}.password-message.success{background:#ecfdf5;border:1px solid #a7f3d0;color:#047857}
.password-security-note{display:flex;gap:11px;background:#f8fafc;border:1px solid #e8edf2;border-radius:12px;padding:14px;margin-top:25px;max-width:620px;color:#667386;font-size:12px;line-height:1.6}.password-security-note strong{color:#334155}
@media(max-width:700px){.password-settings-page{padding:20px 14px 30px}.password-card-head{padding:18px}.password-card-body{padding:20px 18px}.password-title{font-size:25px}.password-actions .password-btn{flex:1}}
`;

function Message({ type = "error", children }) {
  if (!children) return null;
  return <div className={`password-message ${type}`} role={type === "error" ? "alert" : "status"}>{children}</div>;
}

export default function ChangePasswordPage({ username, onCredentialsChanged, onBack }) {
  const [form, setForm] = useState({ currentPassword: "", newPassword: "", confirmPassword: "" });
  const [visible, setVisible] = useState({ currentPassword: false, newPassword: false, confirmPassword: false });
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState("");
  const [messageType, setMessageType] = useState("error");

  const handleChange = (event) => setForm(old => ({ ...old, [event.target.name]: event.target.value }));
  const toggleVisible = (field) => setVisible(old => ({ ...old, [field]: !old[field] }));
  const strongEnough = form.newPassword.length >= 8;
  const matches = form.confirmPassword.length > 0 && form.newPassword === form.confirmPassword;

  const handleSubmit = async (event) => {
    event.preventDefault(); setMessage("");
    if (!form.currentPassword) { setMessageType("error"); setMessage("Enter your current password to continue."); return; }
    if (form.newPassword.length < 8) { setMessageType("error"); setMessage("Your new password must be at least 8 characters long."); return; }
    if (form.newPassword !== form.confirmPassword) { setMessageType("error"); setMessage("New password and confirmation do not match."); return; }
    if (form.currentPassword === form.newPassword) { setMessageType("error"); setMessage("Choose a new password different from your current password."); return; }
    setLoading(true);
    try {
      const response = await fetch(`${API_BASE}/change-password`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, currentPassword: form.currentPassword, newPassword: form.newPassword }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok || !data.success) throw new Error(data.error || "Could not change your password.");
      setForm({ currentPassword: "", newPassword: "", confirmPassword: "" });
      setMessageType("success");
      setMessage("Password updated successfully. Taking you to the login page…");
      window.setTimeout(() => onCredentialsChanged?.("Your password was changed successfully. Please sign in using your new password."), 1400);
    } catch (error) {
      setMessageType("error"); setMessage(error.message || "Unable to connect to the server.");
    } finally { setLoading(false); }
  };

  return <>
    <style>{styles}</style>
    <section className="password-settings-page">
      <div className="password-settings-wrap">
        <p className="password-eyebrow">ACCOUNT SECURITY</p>
        <h2 className="password-title">Change Password</h2>
        <p className="password-subtitle">Update your password to keep your MedTrack account secure.</p>
        <div className="password-card">
          <div className="password-card-head"><div className="password-icon">🔐</div><div><h3>Update your sign-in password</h3><p>Signed in as <strong>{username || "User"}</strong></p></div></div>
          <div className="password-card-body">
            <Message type={messageType}>{message}</Message>
            <form onSubmit={handleSubmit}>
              <div className="password-form-grid">
                {[
                  { name: "currentPassword", label: "Current password", placeholder: "Enter your current password", auto: "current-password" },
                  { name: "newPassword", label: "New password", placeholder: "Create a new password", auto: "new-password" },
                  { name: "confirmPassword", label: "Confirm new password", placeholder: "Re-enter your new password", auto: "new-password" },
                ].map(field => <div key={field.name}>
                  <label className="password-label" htmlFor={field.name}>{field.label}</label>
                  <div className="password-input-wrap"><input className="password-input" id={field.name} name={field.name} type={visible[field.name] ? "text" : "password"} value={form[field.name]} onChange={handleChange} placeholder={field.placeholder} autoComplete={field.auto} minLength={field.name !== "currentPassword" ? 8 : undefined} required /><button className="password-toggle" type="button" onClick={() => toggleVisible(field.name)} aria-label={`${visible[field.name] ? "Hide" : "Show"} ${field.label}`}>{visible[field.name] ? "HIDE" : "SHOW"}</button></div>
                  {field.name === "newPassword" && <div className="password-hint">Use at least 8 characters. Avoid using a password you use elsewhere.</div>}
                </div>)}
              </div>
              <div className="password-rules"><span className={`password-rule ${strongEnough ? "valid" : ""}`}>{strongEnough ? "✓" : "○"} At least 8 characters</span><span className={`password-rule ${matches ? "valid" : ""}`}>{matches ? "✓" : "○"} Passwords match</span></div>
              <div className="password-actions"><button className="password-btn password-primary" type="submit" disabled={loading}>{loading ? "Updating password…" : "Update password →"}</button><button className="password-btn password-outline" type="button" onClick={onBack}>Cancel</button></div>
            </form>
            <div className="password-security-note"><span>🛡️</span><div><strong>Security check</strong><br />Your current password must be verified before the change is saved. After a successful update, you will be signed out and asked to log in again with the new password.</div></div>
          </div>
        </div>
      </div>
    </section>
  </>;
}
