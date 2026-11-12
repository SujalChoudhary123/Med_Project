import { useEffect, useState } from "react";
import { api } from "../../config.js";

const API_BASE = api("/api");

const styles = `
.profile-settings-page{flex:1;min-height:0;overflow-y:auto;padding:26px 30px 40px;color:#172033}
.profile-settings-wrap{max-width:1080px;margin:0 auto}
.profile-eyebrow{font-size:11px;font-weight:800;letter-spacing:1.8px;color:#059669;margin:0 0 9px}
.profile-page-title{font-size:30px;line-height:1.2;margin:0;color:#111827;font-weight:750}
.profile-page-subtitle{margin:9px 0 26px;color:#718096;font-size:14px}
.profile-settings-card{background:#fff;border:1px solid #e4e9ef;border-radius:20px;box-shadow:0 8px 28px #0f172a08;overflow:hidden;margin-bottom:20px}
.profile-card-heading{padding:22px 26px;border-bottom:1px solid #edf0f4;display:flex;align-items:center;gap:14px}
.profile-card-icon{height:46px;width:46px;border-radius:14px;display:grid;place-items:center;background:#e8fbf3;color:#059669;font-size:21px;flex-shrink:0}
.profile-card-heading h3{margin:0 0 4px;font-size:17px;color:#182230}
.profile-card-heading p{margin:0;color:#7b8797;font-size:13px}
.profile-card-body{padding:8px 26px 22px}
.profile-field-row{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:20px 0;border-bottom:1px solid #edf0f4}
.profile-field-row:last-child{border-bottom:0}
.profile-field-copy{min-width:0;flex:1}
.profile-field-label{font-size:12px;color:#788596;font-weight:700;letter-spacing:.3px;margin-bottom:7px}
.profile-field-value{font-size:15px;font-weight:650;color:#263244;overflow-wrap:anywhere}
.profile-field-help{font-size:12px;color:#94a0ae;margin-top:5px}
.profile-inline-editor{display:flex;gap:10px;align-items:center;margin-top:9px;max-width:620px}
.profile-input{width:100%;min-width:0;box-sizing:border-box;padding:12px 13px;border:1px solid #d7dee7;border-radius:10px;background:#fff;color:#172033;font-size:14px;outline:none;transition:border-color .15s,box-shadow .15s}
.profile-input:focus{border-color:#10a779;box-shadow:0 0 0 3px #10a77918}
.profile-btn{border:0;border-radius:10px;padding:11px 16px;font-size:13px;font-weight:750;cursor:pointer;transition:transform .15s,background .15s;white-space:nowrap}
.profile-btn:hover{transform:translateY(-1px)}.profile-btn:disabled{opacity:.6;cursor:wait;transform:none}
.profile-btn-primary{background:#059669;color:#fff}.profile-btn-primary:hover{background:#047857}
.profile-btn-outline{background:#fff;border:1px solid #d9e1e9;color:#3b4758}.profile-btn-outline:hover{background:#f8fafc}
.profile-btn-quiet{background:#effaf5;color:#05845d}.profile-btn-quiet:hover{background:#dcfce9}
.profile-message{padding:13px 15px;border-radius:12px;margin-bottom:18px;font-size:13px;line-height:1.5}
.profile-message.error{background:#fff1f2;border:1px solid #fecdd3;color:#be123c}.profile-message.success{background:#ecfdf5;border:1px solid #a7f3d0;color:#047857}.profile-message.info{background:#eff6ff;border:1px solid #bfdbfe;color:#1d4ed8}
.profile-security-row{display:flex;align-items:center;gap:14px;padding:19px 0}.profile-security-copy{flex:1}.profile-security-copy strong{display:block;font-size:14px;color:#253144;margin-bottom:5px}.profile-security-copy span{font-size:12px;color:#7b8797;line-height:1.5}
.profile-avatar-large{height:54px;width:54px;border-radius:17px;background:#e6faf2;color:#059669;display:grid;place-items:center;font-size:22px;font-weight:800;flex-shrink:0}
@media(max-width:700px){.profile-settings-page{padding:20px 14px 30px}.profile-card-heading{padding:18px}.profile-card-body{padding:4px 18px 18px}.profile-field-row{align-items:flex-start}.profile-inline-editor{flex-direction:column;align-items:stretch}.profile-inline-editor .profile-btn{width:100%}.profile-page-title{font-size:25px}.profile-security-row{align-items:flex-start;flex-wrap:wrap}.profile-security-row .profile-btn{width:100%}}
`;

function Message({ type = "error", children }) {
  if (!children) return null;
  return <div className={`profile-message ${type}`} role={type === "error" ? "alert" : "status"}>{children}</div>;
}

export default function MyProfilePage({ username, onCredentialsChanged, onChangePassword, onBack }) {
  const [profile, setProfile] = useState({ username: username || "", email: "" });
  const [draft, setDraft] = useState({ username: "", email: "" });
  const [editing, setEditing] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [messageType, setMessageType] = useState("error");
  const [challenge, setChallenge] = useState(null);
  const [code, setCode] = useState("");

  useEffect(() => {
    let cancelled = false;
    async function loadProfile() {
      setLoading(true);
      try {
        const response = await fetch(`${API_BASE}/profile?username=${encodeURIComponent(username || "")}`);
        const data = await response.json().catch(() => ({}));
        if (!response.ok || !data.success) throw new Error(data.error || "Could not load your account details.");
        if (!cancelled) {
          const next = { username: data.username || "", email: data.email || "" };
          setProfile(next);
          setDraft(next);
        }
      } catch (error) {
        if (!cancelled) { setMessage(error.message || "Unable to connect to the server."); setMessageType("error"); }
      } finally { if (!cancelled) setLoading(false); }
    }
    loadProfile();
    return () => { cancelled = true; };
  }, [username]);

  const beginEdit = (field) => {
    setEditing(field);
    setMessage("");
  };

  const saveProfile = async (event) => {
    event.preventDefault();
    const cleanUsername = draft.username.trim();
    const cleanEmail = draft.email.trim().toLowerCase();
    if (!cleanUsername || !cleanEmail) { setMessageType("error"); setMessage("Username and email are both required."); return; }
    if (!/^[A-Za-z0-9_.-]{3,30}$/.test(cleanUsername)) { setMessageType("error"); setMessage("Username must be 3–30 characters and use letters, numbers, dots, underscores or hyphens."); return; }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(cleanEmail)) { setMessageType("error"); setMessage("Enter a valid email address."); return; }
    if (cleanUsername === profile.username && cleanEmail === profile.email) { setMessageType("info"); setMessage("No changes to save."); return; }

    setSaving(true); setMessage("");
    try {
      const response = await fetch(`${API_BASE}/profile`, {
        method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ currentUsername: username, username: cleanUsername, email: cleanEmail }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok || !data.success) throw new Error(data.error || "Could not save account changes.");
      // A new email must be confirmed with the code sent to it.
      if (data.otpRequired) {
        setChallenge(data.challengeId);
        setCode("");
        setMessageType("info");
        setMessage(`We sent a 6-digit code to ${data.emailMasked}. Enter it below to finish.`);
        return;
      }
      setProfile({ username: data.username, email: data.email });
      setMessageType("success");
      setMessage("Both account fields have been saved. Redirecting you to sign in…");
      window.setTimeout(() => onCredentialsChanged?.("Your account details changed. Please sign in again using your updated username or email."), 1200);
    } catch (error) {
      setMessageType("error"); setMessage(error.message || "Unable to connect to the server.");
    } finally { setSaving(false); }
  };

  const confirmEmail = async (event) => {
    event.preventDefault();
    setSaving(true); setMessage("");
    try {
      const response = await fetch(`${API_BASE}/auth/verify-otp`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ challengeId: challenge, otp: code.trim() }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok || !data.success) throw new Error(data.error || "That code is not correct.");
      onCredentialsChanged?.("Your email address changed. Please sign in again.");
    } catch (error) {
      setMessageType("error"); setMessage(error.message || "Unable to connect to the server.");
    } finally { setSaving(false); }
  };

  const cancelEdit = () => { setDraft(profile); setEditing(""); setMessage(""); setChallenge(null); };

  return <>
    <style>{styles}</style>
    <section className="profile-settings-page">
      <div className="profile-settings-wrap">
        <p className="profile-eyebrow">ACCOUNT SETTINGS</p>
        <h2 className="profile-page-title">My Profile</h2>
        <p className="profile-page-subtitle">Manage your sign-in details and keep your account information up to date.</p>
        <Message type={messageType}>{message}</Message>
        {challenge && (
          <form className="profile-settings-card" style={{ padding: 18 }} onSubmit={confirmEmail}>
            <div className="profile-inline-editor">
              <input className="profile-input" aria-label="Verification code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="6-digit code" value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ""))} autoFocus required />
              <button type="submit" className="profile-btn profile-btn-primary" disabled={saving || code.length !== 6}>Confirm email</button>
              <button type="button" className="profile-btn profile-btn-outline" onClick={cancelEdit}>Cancel</button>
            </div>
          </form>
        )}
        <div className="profile-settings-card">
          <div className="profile-card-heading">
            <div className="profile-avatar-large">{(profile.username || username || "U").charAt(0).toUpperCase()}</div>
            <div><h3>Personal account</h3><p>Your current account information</p></div>
          </div>
          <div className="profile-card-body">
            {loading ? <p>Loading account details…</p> : <form onSubmit={saveProfile}>
              <div className="profile-field-row">
                <div className="profile-field-copy">
                  <div className="profile-field-label">USERNAME</div>
                  {editing === "username" ? <div className="profile-inline-editor"><input className="profile-input" aria-label="New username" value={draft.username} onChange={e => setDraft(old => ({ ...old, username: e.target.value }))} maxLength={30} autoFocus required /></div> : <><div className="profile-field-value">{draft.username || profile.username || "Not set"}</div><div className="profile-field-help">This is the username you use to sign in.</div></>}
                </div>
                {editing === "username" ? <button type="button" className="profile-btn profile-btn-outline" onClick={() => { setDraft(old => ({ ...old, username: profile.username })); setEditing(""); }}>Done</button> : <button type="button" className="profile-btn profile-btn-quiet" onClick={() => beginEdit("username")}>✎ Edit</button>}
              </div>
              <div className="profile-field-row">
                <div className="profile-field-copy">
                  <div className="profile-field-label">EMAIL ADDRESS</div>
                  {editing === "email" ? <div className="profile-inline-editor"><input className="profile-input" type="email" aria-label="New email address" value={draft.email} onChange={e => setDraft(old => ({ ...old, email: e.target.value }))} autoFocus required /></div> : <><div className="profile-field-value">{draft.email || profile.email || "No email added"}</div><div className="profile-field-help">Used for account identification and sign-in.</div></>}
                </div>
                {editing === "email" ? <button type="button" className="profile-btn profile-btn-outline" onClick={() => { setDraft(old => ({ ...old, email: profile.email })); setEditing(""); }}>Done</button> : <button type="button" className="profile-btn profile-btn-quiet" onClick={() => beginEdit("email")}>✎ Edit</button>}
              </div>
              <div style={{ marginTop: 18, fontSize: 12, color: "#788596", lineHeight: 1.6 }}>You can edit your username and email independently. Your changes will remain on this page until you press the button below. You will be signed out only once, after saving all changes together.</div>
              <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginTop: 20 }}>
                <button type="submit" className="profile-btn profile-btn-primary" disabled={saving}>{saving ? "Saving changes…" : "Save all changes & log out"}</button>
                <button type="button" className="profile-btn profile-btn-outline" onClick={cancelEdit} disabled={saving}>Discard changes</button>
              </div>
            </form>}
          </div>
        </div>
        <div className="profile-settings-card">
          <div className="profile-card-heading"><div className="profile-card-icon">♧</div><div><h3>Security & sign-in</h3><p>Protect your account credentials</p></div></div>
          <div className="profile-card-body"><div className="profile-security-row"><div className="profile-card-icon">🔒</div><div className="profile-security-copy"><strong>Password</strong><span>Change your password whenever you need to. You will be signed out after a successful update.</span></div><button className="profile-btn profile-btn-outline" onClick={() => { setMessage(""); onChangePassword?.(); }}>Change password&nbsp; →</button></div></div>
        </div>
        <button className="profile-btn profile-btn-outline" onClick={() => onBack?.()}>← Back to Dashboard</button>
      </div>
    </section>
  </>;
}
