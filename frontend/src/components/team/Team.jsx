import { useCallback, useEffect, useState } from "react";
import "./team.css";
import Icon from "../layout/Icons.jsx";
import { api } from "../../config.js";

const TEAM_URL = api("/api/team");

const ROLE_INFO = {
  owner: {
    label: "Owner",
    text: "Full access, including team management.",
  },
  pharmacist: {
    label: "Pharmacist",
    text: "Manage medicines, sell, and view reports.",
  },
  cashier: {
    label: "Cashier",
    text: "Sell and look up stock. Cannot change inventory.",
  },
};

const EMPTY_FORM = { username: "", email: "", password: "", role: "cashier" };

function Team() {
  const [members, setMembers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState({ type: "", text: "" });

  const request = async (url, options) => {
    const response = await fetch(url, options);
    const data = await response.json().catch(() => ({}));

    if (!response.ok || data.success === false) {
      throw new Error(data.error || "Something went wrong. Please try again.");
    }

    return data;
  };

  const loadMembers = useCallback(async () => {
    try {
      const data = await request(TEAM_URL);
      setMembers(Array.isArray(data.members) ? data.members : []);
    } catch (error) {
      setMessage({ type: "error", text: error.message });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(loadMembers, 0);
    return () => clearTimeout(timer);
  }, [loadMembers]);

  const handleChange = (event) => {
    const { name, value } = event.target;
    setForm((current) => ({ ...current, [name]: value }));
  };

  const handleAdd = async (event) => {
    event.preventDefault();
    setMessage({ type: "", text: "" });

    if (form.password.length < 8) {
      setMessage({ type: "error", text: "Password must be at least 8 characters." });
      return;
    }

    try {
      setSaving(true);
      await request(TEAM_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(form),
      });

      setMessage({
        type: "success",
        text: `${form.username} was added. Share the username and password with them securely, and ask them to change the password after signing in.`,
      });
      setForm(EMPTY_FORM);
      await loadMembers();
    } catch (error) {
      setMessage({ type: "error", text: error.message });
    } finally {
      setSaving(false);
    }
  };

  const handleRoleChange = async (member, role) => {
    setMessage({ type: "", text: "" });

    try {
      await request(`${TEAM_URL}/${encodeURIComponent(member.username)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ role }),
      });

      setMessage({
        type: "success",
        text: `${member.username} is now a ${ROLE_INFO[role].label}. They will need to sign in again.`,
      });
      await loadMembers();
    } catch (error) {
      setMessage({ type: "error", text: error.message });
    }
  };

  const handleRemove = async (member) => {
    if (!window.confirm(`Remove ${member.username}? They will lose access immediately.`)) {
      return;
    }

    setMessage({ type: "", text: "" });

    try {
      await request(`${TEAM_URL}/${encodeURIComponent(member.username)}`, {
        method: "DELETE",
      });

      setMessage({ type: "success", text: `${member.username} was removed.` });
      await loadMembers();
    } catch (error) {
      setMessage({ type: "error", text: error.message });
    }
  };

  return (
    <div className="team-page">
      {message.text && (
        <div className={`team-message ${message.type}`} role="status">
          {message.text}
        </div>
      )}

      <div className="team-grid">
        <section className="dashboard-card">
          <div className="card-header">
            <div>
              <h2>Team members</h2>
              <p>People who can sign in to your pharmacy</p>
            </div>
            <span className="team-count">
              <Icon name="users" size={16} />
              {members.length}
            </span>
          </div>

          {loading ? (
            <div className="team-empty">Loading team...</div>
          ) : (
            <div className="team-table-wrap">
              <table className="team-table">
                <thead>
                  <tr>
                    <th>Member</th>
                    <th>Role</th>
                    <th className="right">Action</th>
                  </tr>
                </thead>
                <tbody>
                  {members.map((member) => {
                    const isOwner = member.role === "owner";

                    return (
                      <tr key={member.username}>
                        <td>
                          <div className="team-member">
                            <span className="team-avatar">
                              {member.username.charAt(0).toUpperCase()}
                            </span>
                            <div>
                              <strong>{member.username}</strong>
                              <span>{member.email || "No email"}</span>
                            </div>
                          </div>
                        </td>
                        <td>
                          {isOwner ? (
                            <span className="team-role owner">Owner</span>
                          ) : (
                            <select
                              className="team-select"
                              value={member.role}
                              aria-label={`Role for ${member.username}`}
                              onChange={(event) =>
                                handleRoleChange(member, event.target.value)
                              }
                            >
                              <option value="pharmacist">Pharmacist</option>
                              <option value="cashier">Cashier</option>
                            </select>
                          )}
                        </td>
                        <td className="right">
                          {isOwner ? (
                            <span className="team-muted">You</span>
                          ) : (
                            <button
                              type="button"
                              className="delete-button"
                              onClick={() => handleRemove(member)}
                            >
                              Remove
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          <ul className="team-roles">
            {Object.keys(ROLE_INFO).map((key) => (
              <li key={key}>
                <strong>{ROLE_INFO[key].label}</strong>
                <span>{ROLE_INFO[key].text}</span>
              </li>
            ))}
          </ul>
        </section>

        <section className="dashboard-card">
          <div className="card-header">
            <div>
              <h2>Add a team member</h2>
              <p>Create an account for your staff</p>
            </div>
          </div>

          <form className="team-form" onSubmit={handleAdd}>
            <label className="settings-field">
              Username
              <input
                name="username"
                value={form.username}
                onChange={handleChange}
                placeholder="e.g. rahul.counter"
                maxLength={30}
                autoComplete="off"
                required
              />
            </label>

            <label className="settings-field">
              Email address
              <input
                name="email"
                type="email"
                value={form.email}
                onChange={handleChange}
                placeholder="staff@example.com"
                autoComplete="off"
                required
              />
              <small>Used for password recovery.</small>
            </label>

            <label className="settings-field">
              Temporary password
              <input
                name="password"
                type="text"
                value={form.password}
                onChange={handleChange}
                placeholder="At least 8 characters"
                autoComplete="off"
                minLength={8}
                required
              />
              <small>They can change it from My Profile after signing in.</small>
            </label>

            <label className="settings-field">
              Role
              <select name="role" value={form.role} onChange={handleChange}>
                <option value="cashier">Cashier</option>
                <option value="pharmacist">Pharmacist</option>
              </select>
              <small>{ROLE_INFO[form.role].text}</small>
            </label>

            <button type="submit" className="primary-button" disabled={saving}>
              {saving ? "Adding..." : "Add team member"}
            </button>
          </form>
        </section>
      </div>
    </div>
  );
}

export default Team;
