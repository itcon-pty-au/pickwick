export function classify(url, contentType = "") {
  let path;
  try {
    const parsed = new URL(url);
    if (!["http:", "https:"].includes(parsed.protocol)) return null;
    path = parsed.pathname.toLowerCase();
  } catch { return null; }
  const mime = contentType.split(";")[0].trim().toLowerCase();
  if (/\.(m3u8|mpd)$/.test(path) || /mpegurl|dash\+xml/.test(mime)) return "segmented";
  if (/\.(m4a|aac|mp3|ogg|opus)$/.test(path) || mime.startsWith("audio/")) return "audio";
  if (/\.(mp4|webm|m4v|mov)$/.test(path) || ["video/mp4", "video/webm", "video/quicktime", "video/x-m4v"].includes(mime)) return "file";
  return null;
}

export function filename(title, url, mime = "") {
  const path = new URL(url).pathname;
  const ext = path.match(/\.(mp4|webm|m4v|mov)$/i)?.[1].toLowerCase()
    || (mime.includes("webm") ? "webm" : mime.includes("quicktime") ? "mov" : "mp4");
  const safe = title.replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_").replace(/[. ]+$/g, "").slice(0, 120).trim() || "video";
  return `Pickwick/${/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(safe) ? "_" : ""}${safe}.${ext}`;
}

export function preferredMaster(item, items) {
  const source = new URL(item.url);
  const folder = source.pathname.slice(0, source.pathname.lastIndexOf("/") + 1);
  return items.find(other => {
    const url = new URL(other.url);
    return other.frameId === item.frameId && url.origin === source.origin
      && url.pathname.slice(0, url.pathname.lastIndexOf("/") + 1) === folder
      && /\/(playlist|master)\.m3u8$/i.test(url.pathname);
  }) || item;
}
