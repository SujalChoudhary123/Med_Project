// Small helpers shared by the newer pages.

/** Reads a JSON reply and turns any failure into an Error with a readable message. */
export async function readJson(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok || data.success === false) {
    throw new Error(data.error || "Something went wrong. Please try again.");
  }
  return data;
}

export const jsonPost = (body, method = "POST") => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

/**
 * A reference that travels with a bill or purchase. When the connection drops and
 * the person tries again, the server sees the same reference and does not save twice.
 */
export const newRef = () =>
  `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;

/** Turns the browser's "Failed to fetch" into something a shop owner can act on. */
export const friendlyError = (error) =>
  error instanceof TypeError || /fetch|network/i.test(error?.message || "")
    ? "No connection to the server. Nothing was lost: check the internet and press the button again."
    : error?.message || "Something went wrong. Please try again.";

export const todayIso = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(
    now.getDate()
  ).padStart(2, "0")}`;
};
