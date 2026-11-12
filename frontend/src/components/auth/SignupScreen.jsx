
import { useState } from "react";
import { API_ORIGIN } from "../../config.js";
import {
  authInputStyle,
  authLabelStyle,
  authButtonStyle,
} from "../../utils/appUtils.js";
import AuthShell from "./AuthShell.jsx";
import "../business.css";

const API_BASE_URL = API_ORIGIN;

function SignupScreen({ onLoginSuccess, onBackToLogin }) {
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [acceptTerms, setAcceptTerms] = useState(false);

  const [otp, setOtp] = useState("");
  const [challengeId, setChallengeId] = useState("");
  const [emailMasked, setEmailMasked] = useState("");
  const [otpStep, setOtpStep] = useState(false);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  const handleSubmit = async (event) => {
    event.preventDefault();

    const cleanUsername = username.trim();
    const cleanEmail = email.trim().toLowerCase();

    if (
      !cleanUsername ||
      !cleanEmail ||
      !password ||
      !confirmPassword
    ) {
      setError("Please fill in all fields.");
      return;
    }

    if (!/^[A-Za-z0-9_.-]{3,30}$/.test(cleanUsername)) {
      setError(
        "Username must be 3–30 characters and may contain letters, numbers, dots, underscores or hyphens."
      );
      return;
    }

    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(cleanEmail)) {
      setError("Please enter a valid email address.");
      return;
    }

    if (password.length < 8) {
      setError("Password must be at least 8 characters long.");
      return;
    }

    if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) {
      setError("Password must contain at least one letter and one number.");
      return;
    }

    if (password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }

    if (!acceptTerms) {
      setError("Please accept the Terms of Service and Privacy Policy.");
      return;
    }

    try {
      setLoading(true);
      setError("");
      setMessage("");

      const response = await fetch(`${API_BASE_URL}/api/signup`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          username: cleanUsername,
          email: cleanEmail,
          password,
          acceptTerms: true,
        }),
      });

      const data = await response.json().catch(() => ({}));

      if (!response.ok || !data.success) {
        setError(data.error || "Unable to start signup verification.");
        return;
      }

      // Signup only starts verification. It does NOT log the user in.
      if (!data.otpRequired || !data.challengeId) {
        setError(
          "The server did not return an OTP verification session. Please try again."
        );
        return;
      }

      setChallengeId(data.challengeId);
      setEmailMasked(data.emailMasked || cleanEmail);
      setOtpStep(true);
      setMessage("Verification code sent. Check your email inbox and spam folder.");
    } catch (err) {
      console.error("Signup error:", err);
      setError(
        "Unable to connect to the server. Make sure the Java API server is running."
      );
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOtp = async (event) => {
    event.preventDefault();

    if (!/^\d{6}$/.test(otp.trim())) {
      setError("Enter the 6-digit OTP sent to your email.");
      return;
    }

    if (!challengeId) {
      setError("Your verification session is missing. Please start signup again.");
      return;
    }

    try {
      setLoading(true);
      setError("");
      setMessage("");

      const response = await fetch(
        `${API_BASE_URL}/api/auth/verify-otp`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            challengeId,
            otp: otp.trim(),
          }),
        }
      );

      const data = await response.json().catch(() => ({}));

      if (!response.ok || !data.success || !data.username) {
        setError(data.error || "OTP verification failed. Please try again.");
        return;
      }

      // Login is allowed ONLY after successful OTP verification.
      onLoginSuccess(
        data.username,
        "Your MedTrack account was verified and created successfully."
      );
    } catch (err) {
      console.error("OTP verification error:", err);
      setError("Unable to verify OTP. Please check that the server is running.");
    } finally {
      setLoading(false);
    }
  };

  const handleResendOtp = async () => {
    if (!challengeId) {
      setError("Your verification session is missing. Please start signup again.");
      return;
    }

    try {
      setLoading(true);
      setError("");
      setMessage("");

      const response = await fetch(
        `${API_BASE_URL}/api/auth/resend-otp`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ challengeId }),
        }
      );

      const data = await response.json().catch(() => ({}));

      if (!response.ok || !data.success || !data.challengeId) {
        setError(data.error || "Unable to resend OTP.");
        return;
      }

      setChallengeId(data.challengeId);
      setOtp("");
      setMessage("A new verification code has been sent to your email.");
    } catch (err) {
      console.error("Resend OTP error:", err);
      setError("Unable to connect to the server.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthShell
      title={otpStep ? "Verify your email" : "Create your account"}
      subtitle={
        otpStep
          ? "One last step to secure your account"
          : "Set up your pharmacy workspace in minutes"
      }
    >
      {!otpStep ? (
        <form onSubmit={handleSubmit}>
          <label style={authLabelStyle}>Username</label>
          <input
            type="text"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            placeholder="Choose a username"
            autoComplete="username"
            maxLength={30}
            disabled={loading}
            style={authInputStyle}
          />

          <label style={authLabelStyle}>Email address</label>
          <input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="you@example.com"
            autoComplete="email"
            disabled={loading}
            style={authInputStyle}
          />

          <label style={authLabelStyle}>Password</label>
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            placeholder="8+ characters, with a letter and a number"
            autoComplete="new-password"
            disabled={loading}
            style={authInputStyle}
          />

          <label style={authLabelStyle}>Confirm password</label>
          <input
            type="password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            placeholder="Re-enter your password"
            autoComplete="new-password"
            disabled={loading}
            style={authInputStyle}
          />

          <label className="terms-check">
            <input
              type="checkbox"
              checked={acceptTerms}
              disabled={loading}
              onChange={(event) => setAcceptTerms(event.target.checked)}
            />
            <span>
              I agree to the{" "}
              <a href="/terms.html" target="_blank" rel="noopener noreferrer">Terms of Service</a>{" "}
              and{" "}
              <a href="/privacy.html" target="_blank" rel="noopener noreferrer">Privacy Policy</a>.
            </span>
          </label>

          {error && (
            <div
              role="alert"
              style={{
                marginBottom: 16,
                padding: 11,
                borderRadius: 10,
                background: "#fef2f2",
                color: "#b91c1c",
                border: "1px solid #fecaca",
                fontSize: 13,
              }}
            >
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            style={authButtonStyle(loading)}
          >
            {loading ? "Sending verification code..." : "Create account"}
          </button>
        </form>
      ) : (
        <form onSubmit={handleVerifyOtp}>
          <p style={{ color: "#4b5563", lineHeight: 1.6 }}>
            We sent a 6-digit verification code to{" "}
            <strong>{emailMasked}</strong>. Enter it below to finish creating
            your account.
          </p>

          <label style={authLabelStyle}>Email verification code</label>
          <input
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            value={otp}
            onChange={(event) =>
              setOtp(event.target.value.replace(/\D/g, "").slice(0, 6))
            }
            placeholder="Enter 6-digit OTP"
            maxLength={6}
            disabled={loading}
            style={authInputStyle}
          />

          {message && (
            <div
              role="status"
              style={{
                marginBottom: 16,
                padding: 11,
                borderRadius: 10,
                background: "#ecfdf5",
                color: "#047857",
                border: "1px solid #a7f3d0",
                fontSize: 13,
                lineHeight: 1.5,
              }}
            >
              {message}
            </div>
          )}

          {error && (
            <div
              role="alert"
              style={{
                marginBottom: 16,
                padding: 11,
                borderRadius: 10,
                background: "#fef2f2",
                color: "#b91c1c",
                border: "1px solid #fecaca",
                fontSize: 13,
                lineHeight: 1.5,
              }}
            >
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            style={authButtonStyle(loading)}
          >
            {loading ? "Verifying..." : "Verify email & create account"}
          </button>

          <button
            type="button"
            onClick={handleResendOtp}
            disabled={loading}
            style={{
              width: "100%",
              marginTop: 12,
              padding: "11px",
              border: "1px solid #d1d5db",
              borderRadius: 10,
              background: "#ffffff",
              color: "#059669",
              fontWeight: 600,
              cursor: loading ? "not-allowed" : "pointer",
            }}
          >
            Resend OTP
          </button>

          <button
            type="button"
            onClick={() => {
              setOtpStep(false);
              setChallengeId("");
              setOtp("");
              setError("");
              setMessage("");
            }}
            disabled={loading}
            style={{
              display: "block",
              margin: "18px auto 0",
              border: 0,
              background: "transparent",
              color: "#6b7280",
              cursor: "pointer",
            }}
          >
            Back to signup
          </button>
        </form>
      )}

      {!otpStep && (
        <p
          style={{
            margin: "22px 0 0",
            textAlign: "center",
            color: "#6b7280",
            fontSize: 14,
          }}
        >
          Already have an account?{" "}
          <button
            type="button"
            onClick={onBackToLogin}
            disabled={loading}
            style={{
              border: 0,
              padding: 0,
              background: "transparent",
              color: "#059669",
              fontWeight: 700,
              cursor: "pointer",
            }}
          >
            Back to login
          </button>
        </p>
      )}
    </AuthShell>
  );
}

export default SignupScreen;
