import test from "node:test";
import assert from "node:assert/strict";
import { classify, filename, preferredMaster } from "./media.mjs";
test("direct media with query tokens and MIME-only URLs", () => {
  assert.equal(classify("https://example.com/video.MP4?token=secret"), "file");
  assert.equal(classify("https://example.com/play", "video/webm; charset=binary"), "file");
  assert.equal(classify("https://example.com/audio", "audio/mp4"), "audio");
  assert.equal(classify("https://example.com/audio.m4a"), "audio");
});
test("child tracks resolve to the captured master only in the same frame and folder", () => {
  const item = { url: "https://v2.pkflx.com/hls/season/26/video_h264_360p.mp4", frameId: 2 };
  const master = { url: "https://v2.pkflx.com/hls/season/26/playlist.m3u8", frameId: 2 };
  assert.equal(preferredMaster(item, [item, master]), master);
  assert.equal(preferredMaster(item, [{ ...master, frameId: 3 }]), item);
  assert.equal(preferredMaster(item, [{ ...master, url: master.url.replace("/26/", "/27/") }]), item);
});
test("playlists are not downloadable files", () => {
  assert.equal(classify("https://example.com/a.m3u8"), "segmented");
  assert.equal(classify("https://example.com/a", "application/dash+xml"), "segmented");
  assert.equal(classify("https://example.com/segment.ts", "video/mp2t"), null);
  assert.equal(classify("blob:https://example.com/id", "video/mp4"), null);
  assert.equal(classify("not a URL"), null);
});
test("safe filenames", () => {
  assert.equal(filename("Episode: 1/2?", "https://example.com/a.webm"), "Pickwick/Episode_ 1_2_.webm");
  assert.equal(filename("CON", "https://example.com/a"), "Pickwick/_CON.mp4");
  assert.equal(filename("...", "https://example.com/a"), "Pickwick/video.mp4");
});
