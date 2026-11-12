// Downloads a file from the API. A plain link cannot do this because the
// request needs the sign-in token, so the file is fetched and then saved.
export async function downloadFile(url, fallbackName) {
  const response = await fetch(url);

  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.error || "The download failed. Please try again.");
  }

  const blob = await response.blob();
  const disposition = response.headers.get("Content-Disposition") || "";
  const match = /filename="?([^";]+)"?/.exec(disposition);

  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = match ? match[1] : fallbackName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 1000);
}
