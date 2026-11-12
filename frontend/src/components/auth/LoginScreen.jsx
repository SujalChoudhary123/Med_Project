
import { useEffect, useRef, useState } from "react";
import { API_ORIGIN } from "../../config.js";
import {
  authInputStyle,
  authLabelStyle,
  authButtonStyle,
} from "../../utils/appUtils.js";
import AuthShell from "./AuthShell.jsx";

const API_BASE_URL = API_ORIGIN;
const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID;

function LoginScreen({ onLoginSuccess, onShowSignup }) {
  const googleButtonRef = useRef(null);

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [otp, setOtp] = useState("");
  const [challengeId, setChallengeId] = useState("");
  const [emailMasked, setEmailMasked] = useState("");
  const [otpStep, setOtpStep] = useState(false);

  const [screen, setScreen] = useState("login");
  const [resetEmail, setResetEmail] = useState("");
  const [resetToken, setResetToken] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const request = async (endpoint, body) => {
    const response = await fetch(`${API_BASE_URL}${endpoint}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });

    const data = await response.json().catch(() => ({}));

    if (!response.ok || !data.success) {
      throw new Error(
        data.error || "The request could not be completed."
      );
    }

    return data;
  };

  const resetMessages = () => {
    setError("");
    setNotice("");
  };

  // Google sign-in
  useEffect(() => {
    let cancelled = false;
    let retryTimer;

    const initializeGoogle = () => {
      if (cancelled) return;

      if (!GOOGLE_CLIENT_ID) {
        setError(
          "Google Client ID is missing. Check your frontend .env file."
        );
        return;
      }

      if (!window.google?.accounts?.id) {
        retryTimer = window.setTimeout(initializeGoogle, 250);
        return;
      }

      if (!googleButtonRef.current) return;

      googleButtonRef.current.innerHTML = "";

      window.google.accounts.id.initialize({
        client_id: GOOGLE_CLIENT_ID,
        callback: async (googleResponse) => {
          if (!googleResponse?.credential) {
            setError("Google did not return a valid sign-in credential.");
            return;
          }

          try {
            setLoading(true);
            resetMessages();

            const data = await request("/api/auth/google", {
              credential: googleResponse.credential,
            });

            // Existing Google account logs in directly.
            if (!data.otpRequired && data.username) {
              onLoginSuccess(
                data.username,
                data.message || "Google login successful."
              );
              return;
            }

            // First-time Google signup requires OTP.
            if (!data.challengeId) {
              throw new Error(
                "Google sign-in could not start verification."
              );
            }

            setChallengeId(data.challengeId);
            setEmailMasked(data.emailMasked || "");
            setOtp("");
            setOtpStep(true);
            setScreen("login");

            setNotice(
              data.message ||
                "Enter the verification code sent to your email."
            );
          } catch (err) {
            setError(err.message || "Google sign-in failed.");
          } finally {
            setLoading(false);
          }
        },
      });

      window.google.accounts.id.renderButton(
        googleButtonRef.current,
        {
          type: "standard",
          theme: "outline",
          size: "large",
          text: "continue_with",
          shape: "rectangular",
          width: Math.max(
            220,
            Math.min(
              googleButtonRef.current.parentElement?.clientWidth || 320,
              400
            )
          ),
        }
      );
    };

    initializeGoogle();

    return () => {
      cancelled = true;
      if (retryTimer) window.clearTimeout(retryTimer);
    };
  // Google's button must be set up once only; onLoginSuccess changes on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Password login
  const handleLogin = async (event) => {
    event.preventDefault();

    if (!username.trim() || !password) {
      setError("Please enter your username/email and password.");
      return;
    }

    try {
      setLoading(true);
      resetMessages();

      const data = await request("/api/auth/login", {
        username: username.trim(),
        password,
      });

      // Backend currently supports direct password login.
      if (data.username) {
        onLoginSuccess(
          data.username,
          data.message || "Welcome back!"
        );
        return;
      }

      if (data.otpRequired && data.challengeId) {
        setChallengeId(data.challengeId);
        setEmailMasked(data.emailMasked || "");
        setOtp("");
        setOtpStep(true);
        setNotice(
          data.message || "Verification code sent to your email."
        );
        return;
      }

      throw new Error("The server returned an unexpected login response.");
    } catch (err) {
      setError(err.message || "Unable to log in.");
    } finally {
      setLoading(false);
    }
  };

  // OTP verification: login, signup or Google signup.
  const handleVerifyOtp = async (event) => {
    event.preventDefault();

    if (!challengeId) {
      setError("Your verification session has expired. Please start again.");
      setOtpStep(false);
      return;
    }

    if (!/^\d{6}$/.test(otp.trim())) {
      setError("Enter the 6-digit verification code.");
      return;
    }

    try {
      setLoading(true);
      resetMessages();

      const data = await request("/api/auth/verify-otp", {
        challengeId,
        otp: otp.trim(),
      });

      // This login screen's OTP is for signup/Google/OTP login.
      // Forgot-password OTP is handled separately below.
      if (!data.username) {
        throw new Error(
          "Verification did not return a valid user. Please try again."
        );
      }

      onLoginSuccess(
        data.username,
        "Verification successful. Welcome to MedTrack!"
      );
    } catch (err) {
      setError(err.message || "OTP verification failed.");
    } finally {
      setLoading(false);
    }
  };

  const handleResendOtp = async () => {
    if (!challengeId) {
      setError("Please start the verification process again.");
      return;
    }

    try {
      setLoading(true);
      resetMessages();

      const data = await request("/api/auth/resend-otp", {
        challengeId,
      });

      if (!data.challengeId) {
        throw new Error("The server did not return a new OTP session.");
      }

      setChallengeId(data.challengeId);
      setEmailMasked(data.emailMasked || emailMasked);
      setOtp("");

      setNotice(data.message || "A new verification code has been sent.");
    } catch (err) {
      setError(err.message || "Unable to resend the verification code.");
    } finally {
      setLoading(false);
    }
  };

  // Start password recovery
  const handleForgotPassword = async (event) => {
    event.preventDefault();

    if (!resetEmail.trim()) {
      setError("Please enter your registered email address.");
      return;
    }

    try {
      setLoading(true);
      resetMessages();

      const data = await request("/api/auth/forgot-password", {
        email: resetEmail.trim(),
      });

      if (!data.challengeId) {
        throw new Error("The server did not create an OTP session.");
      }

      setChallengeId(data.challengeId);
      setEmailMasked(data.emailMasked || resetEmail.trim());
      setOtp("");
      setScreen("forgot-otp");

      setNotice(
        data.message || "A verification code has been sent to your email."
      );
    } catch (err) {
      setError(err.message || "Unable to start password recovery.");
    } finally {
      setLoading(false);
    }
  };

  // Verify password recovery OTP
  const handleVerifyForgotOtp = async (event) => {
    event.preventDefault();

    if (!challengeId) {
      setError("Your OTP session has expired. Please start again.");
      return;
    }

    if (!/^\d{6}$/.test(otp.trim())) {
      setError("Enter the 6-digit verification code.");
      return;
    }

    try {
      setLoading(true);
      resetMessages();

      const data = await request("/api/auth/verify-otp", {
        challengeId,
        otp: otp.trim(),
      });

      if (!data.requiresPasswordReset || !data.resetToken) {
        throw new Error(
          "OTP verification did not return a password reset token."
        );
      }

      setResetToken(data.resetToken);
      setOtp("");
      setScreen("reset-password");
      setNotice("Email verified. You can now create a new password.");
    } catch (err) {
      setError(err.message || "OTP verification failed.");
    } finally {
      setLoading(false);
    }
  };

  // Save new password
  const handleResetPassword = async (event) => {
    event.preventDefault();

    if (newPassword.length < 8) {
      setError("Password must be at least 8 characters long.");
      return;
    }

    if (newPassword !== confirmPassword) {
      setError("The passwords do not match.");
      return;
    }

    if (!resetToken) {
      setError("Your reset session is missing. Please verify your email again.");
      return;
    }

    try {
      setLoading(true);
      resetMessages();

      const data = await request("/api/auth/reset-password", {
        resetToken,
        newPassword,
      });

      setPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setResetToken("");
      setChallengeId("");
      setOtpStep(false);
      setScreen("login");

      setNotice(
        data.message || "Password updated successfully. Please log in."
      );
    } catch (err) {
      setError(err.message || "Unable to reset your password.");
    } finally {
      setLoading(false);
    }
  };

  const backToLogin = () => {
    setScreen("login");
    setOtpStep(false);
    setChallengeId("");
    setEmailMasked("");
    setOtp("");
    setResetToken("");
    setNewPassword("");
    setConfirmPassword("");
    resetMessages();
  };

  const messageBoxStyle = {
    marginBottom: 16,
    padding: "12px 14px",
    borderRadius: 10,
    fontSize: 13,
    lineHeight: 1.5,
  };

  const renderMessages = () => (
    <>
      {error && (
        <div
          role="alert"
          style={{
            ...messageBoxStyle,
            background: "#fef2f2",
            color: "#b91c1c",
            border: "1px solid #fecaca",
          }}
        >
          {error}
        </div>
      )}

      {notice && (
        <div
          role="status"
          style={{
            ...messageBoxStyle,
            background: "#ecfdf5",
            color: "#047857",
            border: "1px solid #a7f3d0",
          }}
        >
          {notice}
        </div>
      )}
    </>
  );

  const secondaryButtonStyle = {
    width: "100%",
    marginTop: 12,
    padding: 10,
    border: "1px solid #e5e7eb",
    borderRadius: 10,
    background: "#fff",
    color: "#374151",
    cursor: loading ? "not-allowed" : "pointer",
    fontWeight: 600,
  };

  const textButtonStyle = {
    border: 0,
    padding: 0,
    background: "transparent",
    color: "#059669",
    fontWeight: 700,
    cursor: loading ? "not-allowed" : "pointer",
  };

  return (
    <AuthShell hideHeading={!(screen === "login" && !otpStep)}>
      {screen === "login" && !otpStep && (
        <>
          <form onSubmit={handleLogin}>
            <label style={authLabelStyle}>Username or Email</label>
            <input
              type="text"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              placeholder="Enter username or email"
              autoComplete="username"
              disabled={loading}
              required
              style={authInputStyle}
            />

            <label style={authLabelStyle}>Password</label>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="Enter password"
              autoComplete="current-password"
              disabled={loading}
              required
              style={authInputStyle}
            />

            <div style={{ textAlign: "right", margin: "-4px 0 18px" }}>
              <button
                type="button"
                disabled={loading}
                onClick={() => {
                  resetMessages();
                  setResetEmail("");
                  setScreen("forgot-email");
                }}
                style={textButtonStyle}
              >
                Forgot Password?
              </button>
            </div>

            {renderMessages()}

            <button
              type="submit"
              disabled={loading}
              style={authButtonStyle(loading)}
            >
              {loading ? "Please wait..." : "Login"}
            </button>
          </form>

          <div style={{ margin: "22px 0 18px", textAlign: "center" }}>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 12,
                color: "#9ca3af",
                fontSize: 12,
                marginBottom: 16,
              }}
            >
              <span style={{ flex: 1, height: 1, background: "#e5e7eb" }} />
              OR CONTINUE WITH
              <span style={{ flex: 1, height: 1, background: "#e5e7eb" }} />
            </div>

            <div
              ref={googleButtonRef}
              style={{
                display: "flex",
                justifyContent: "center",
                minHeight: 42,
                width: "100%",
              }}
            />
          </div>

          <p
            style={{
              margin: "22px 0 0",
              textAlign: "center",
              color: "#6b7280",
              fontSize: 14,
            }}
          >
            Don&apos;t have an account?{" "}
            <button
              type="button"
              onClick={onShowSignup}
              disabled={loading}
              style={textButtonStyle}
            >
              Create account
            </button>
          </p>
        </>
      )}

      {screen === "login" && otpStep && (
        <form onSubmit={handleVerifyOtp}>
          <h2 style={{ margin: "0 0 8px", fontSize: 22, color: "#111827" }}>
            Verify your email
          </h2>

          <p style={{ margin: "0 0 20px", color: "#6b7280", fontSize: 14, lineHeight: 1.6 }}>
            Enter the 6-digit code sent to{" "}
            <strong>{emailMasked || "your registered email"}</strong>.
            The code expires in 5 minutes.
          </p>

          <label style={authLabelStyle}>Verification Code</label>
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
            required
            style={{
              ...authInputStyle,
              letterSpacing: "6px",
              textAlign: "center",
              fontSize: 20,
              fontWeight: 700,
            }}
          />

          {renderMessages()}

          <button
            type="submit"
            disabled={loading || otp.length !== 6}
            style={authButtonStyle(loading || otp.length !== 6)}
          >
            {loading ? "Verifying..." : "Verify OTP & Continue"}
          </button>

          <button
            type="button"
            onClick={handleResendOtp}
            disabled={loading}
            style={{ ...textButtonStyle, width: "100%", marginTop: 16 }}
          >
            Resend verification code
          </button>

          <button
            type="button"
            onClick={backToLogin}
            disabled={loading}
            style={secondaryButtonStyle}
          >
            Back to login
          </button>
        </form>
      )}

      {screen === "forgot-email" && (
        <form onSubmit={handleForgotPassword}>
          <h2 style={{ margin: "0 0 8px", fontSize: 22, color: "#111827" }}>
            Forgot Password?
          </h2>

          <p style={{ margin: "0 0 20px", color: "#6b7280", fontSize: 14, lineHeight: 1.6 }}>
            Enter the email address linked to your MedTrack account. We&apos;ll
            send you a verification code.
          </p>

          <label style={authLabelStyle}>Registered Email Address</label>
          <input
            type="email"
            value={resetEmail}
            onChange={(event) => setResetEmail(event.target.value)}
            placeholder="you@example.com"
            autoComplete="email"
            disabled={loading}
            required
            style={authInputStyle}
          />

          {renderMessages()}

          <button
            type="submit"
            disabled={loading}
            style={authButtonStyle(loading)}
          >
            {loading ? "Sending OTP..." : "Send Verification Code"}
          </button>

          <button
            type="button"
            onClick={backToLogin}
            disabled={loading}
            style={secondaryButtonStyle}
          >
            Back to login
          </button>
        </form>
      )}

      {screen === "forgot-otp" && (
        <form onSubmit={handleVerifyForgotOtp}>
          <h2 style={{ margin: "0 0 8px", fontSize: 22, color: "#111827" }}>
            Verify Your Email
          </h2>

          <p style={{ margin: "0 0 20px", color: "#6b7280", fontSize: 14, lineHeight: 1.6 }}>
            Enter the 6-digit code sent to{" "}
            <strong>{emailMasked || resetEmail}</strong>.
            The code expires in 5 minutes.
          </p>

          <label style={authLabelStyle}>Verification Code</label>
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
            required
            style={{
              ...authInputStyle,
              letterSpacing: "6px",
              textAlign: "center",
              fontSize: 20,
              fontWeight: 700,
            }}
          />

          {renderMessages()}

          <button
            type="submit"
            disabled={loading || otp.length !== 6}
            style={authButtonStyle(loading || otp.length !== 6)}
          >
            {loading ? "Verifying..." : "Verify Email"}
          </button>

          <button
            type="button"
            onClick={handleResendOtp}
            disabled={loading}
            style={{ ...textButtonStyle, width: "100%", marginTop: 16 }}
          >
            Resend verification code
          </button>

          <button
            type="button"
            onClick={() => {
              setScreen("forgot-email");
              setChallengeId("");
              setOtp("");
              resetMessages();
            }}
            disabled={loading}
            style={secondaryButtonStyle}
          >
            Change email address
          </button>
        </form>
      )}

      {screen === "reset-password" && (
        <form onSubmit={handleResetPassword}>
          <h2 style={{ margin: "0 0 8px", fontSize: 22, color: "#111827" }}>
            Create New Password
          </h2>

          <p style={{ margin: "0 0 20px", color: "#6b7280", fontSize: 14, lineHeight: 1.6 }}>
            Your email is verified. Choose a new password with at least 8
            characters.
          </p>

          <label style={authLabelStyle}>New Password</label>
          <input
            type="password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            placeholder="Enter new password"
            autoComplete="new-password"
            minLength={8}
            disabled={loading}
            required
            style={authInputStyle}
          />

          <label style={authLabelStyle}>Confirm New Password</label>
          <input
            type="password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            placeholder="Re-enter new password"
            autoComplete="new-password"
            minLength={8}
            disabled={loading}
            required
            style={authInputStyle}
          />

          {renderMessages()}

          <button
            type="submit"
            disabled={loading || newPassword.length < 8 || !confirmPassword}
            style={authButtonStyle(
              loading || newPassword.length < 8 || !confirmPassword
            )}
          >
            {loading ? "Updating password..." : "Update Password"}
          </button>

          <button
            type="button"
            onClick={backToLogin}
            disabled={loading}
            style={secondaryButtonStyle}
          >
            Cancel and return to login
          </button>
        </form>
      )}
    </AuthShell>
  );
}

export default LoginScreen;
