import express from "express";
import fs from "node:fs/promises";
import path from "node:path";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { chromium } from "playwright";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, "..");
const outputRoot = path.join(rootDir, "ShortsAutoOutput");
const profileDir = path.join(rootDir, ".chatgpt-profile");
const maxPrompts = 200;
const cdpUrl = process.env.EDGE_CDP_URL || "http://127.0.0.1:9222";
const typingSettleMs = 150;
const sendSettleMs = 150;
const promptSentPollMs = 200;
const promptSentTimeoutMs = 10000;
const promptSubmitMaxAttempts = 3;
const generationPollMs = 2000;
const promptGapMs = 500;
const edgePaths = [
  "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"
];

const app = express();
const execFileAsync = promisify(execFile);
app.use(express.json({ limit: "2mb" }));
app.use(express.static(path.join(__dirname, "public")));
app.use("/output", express.static(outputRoot));

let browser = null;
let browserContext = null;
let page = null;
let connectedByCdp = false;
let running = false;
let paused = false;
let progressMessage = "Idle";
let currentLog = [];
let lastRunId = 0;
let lastRunResult = null;
const downloadedFileMap = new Map();

function now() {
  return new Date().toLocaleTimeString("ko-KR", { hour12: false });
}

function log(message) {
  const line = `[${now()}] ${message}`;
  currentLog.unshift(line);
  console.log(line);
}

function setProgress(message) {
  progressMessage = message;
  log(message);
}

async function waitIfPaused() {
  while (running && paused) await page.waitForTimeout(500);
}

function sanitize(value) {
  return (value || "shorts_project").replace(/[\\/:*?"<>|]/g, "_").trim() || "shorts_project";
}

function normalizeLocalPath(value) {
  const raw = String(value || "").trim();
  if (!raw) return "";
  return path.resolve(raw);
}

function isChatGptUrl(url) {
  return String(url || "").toLowerCase().includes("chatgpt.com");
}

function normalizeTargetUrl(value) {
  const raw = String(value || "").trim();
  if (!raw) return "";
  const withProtocol = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`;
  const parsed = new URL(withProtocol);
  if (!isChatGptUrl(parsed.href)) throw new Error("Target URL must include chatgpt.com.");
  return parsed.href;
}

function resetBrowserRefs() {
  browser = null;
  browserContext = null;
  page = null;
  connectedByCdp = false;
}

async function ensureBrowser({ allowLaunch = true } = {}) {
  if (browserContext) {
    try {
      page = await pickWorkingPage(browserContext);
      return browserContext;
    } catch {
      resetBrowserRefs();
    }
  }

  await fs.mkdir(outputRoot, { recursive: true });
  const cdpContext = await tryConnectExistingEdge();
  if (cdpContext) {
    browserContext = cdpContext;
    connectedByCdp = true;
    page = await pickWorkingPage(browserContext);
    log("Connected to existing Edge browser.");
    return browserContext;
  }

  if (!allowLaunch) return null;

  connectedByCdp = false;
  browserContext = await launchEdgeContext();
  page = await pickWorkingPage(browserContext);
  log("Opened automation Edge browser.");
  return browserContext;
}

async function tryConnectExistingEdge() {
  try {
    browser = await chromium.connectOverCDP(cdpUrl, { timeout: 1500 });
    return browser.contexts()[0] || null;
  } catch {
    return null;
  }
}

async function launchEdgeContext() {
  const launchOptions = {
    channel: "msedge",
    headless: false,
    acceptDownloads: true,
    viewport: { width: 1280, height: 900 },
    downloadsPath: outputRoot
  };
  try {
    return await chromium.launchPersistentContext(profileDir, launchOptions);
  } catch (edgeError) {
    for (const executablePath of edgePaths) {
      try {
        await fs.access(executablePath);
        return await chromium.launchPersistentContext(profileDir, {
          ...launchOptions,
          channel: undefined,
          executablePath
        });
      } catch {
        // Try next Edge path.
      }
    }
    throw edgeError;
  }
}

async function pickWorkingPage(context) {
  const pages = context.pages().filter((candidate) => !candidate.isClosed());
  const chatPage = [...pages].reverse().find((candidate) => isChatGptUrl(candidate.url()));
  const selected = chatPage || pages[pages.length - 1] || await context.newPage();
  selected.setDefaultTimeout(10000);
  return selected;
}

function getOpenPages() {
  return browserContext.pages().filter((candidate) => !candidate.isClosed());
}

function findChatGptPage(pages, normalizedTarget = "") {
  const reversedPages = [...pages].reverse();
  if (normalizedTarget) {
    const targetPage = reversedPages.find((candidate) => {
      const currentUrl = candidate.url();
      return currentUrl === normalizedTarget || currentUrl.startsWith(normalizedTarget);
    });
    if (targetPage) return targetPage;
  }
  return reversedPages.find((candidate) => isChatGptUrl(candidate.url())) || null;
}

async function ensureChatGptPage(targetUrl = "", { allowLaunch = true } = {}) {
  const context = await ensureBrowser({ allowLaunch });
  if (!context) return { found: false, page: null };

  const normalizedTarget = targetUrl ? normalizeTargetUrl(targetUrl) : "";
  const pages = getOpenPages();
  const chatPage = findChatGptPage(pages, normalizedTarget);

  if (chatPage) {
    page = chatPage;
    await page.bringToFront().catch(() => {});
    return { found: true, page };
  }

  if (normalizedTarget && !connectedByCdp) {
    page = page && !page.isClosed() ? page : pages[pages.length - 1] || await browserContext.newPage();
    await page.goto(normalizedTarget, { waitUntil: "domcontentloaded" });
    await page.bringToFront().catch(() => {});
    return { found: true, page };
  }

  page = page && !page.isClosed() ? page : pages[pages.length - 1] || null;
  if (page) await page.bringToFront().catch(() => {});
  return { found: false, page };
}

async function getConnectionStatus(targetUrl = "") {
  const result = await ensureChatGptPage(targetUrl, { allowLaunch: false });
  const title = page ? await page.title().catch(() => "") : "";
  const url = page ? page.url() : "";
  return {
    connected: result.found,
    usingCdp: connectedByCdp,
    title,
    url,
    message: result.found
      ? "ChatGPT tab connected."
      : "ChatGPT tab was not found. Open Edge with run-edge-debug-chatgpt.bat, then open chatgpt.com."
  };
}

async function findPromptBox() {
  const selectors = [
    "#prompt-textarea",
    "[data-testid='composer-root'] [contenteditable='true']",
    "form [contenteditable='true']",
    "div[contenteditable='true'][id='prompt-textarea']",
    "textarea",
    "[contenteditable='true']",
    "div.ProseMirror",
    "[placeholder*='무엇이든']",
    "[data-placeholder*='무엇이든']",
    "[data-placeholder*='Message']",
    "[aria-label*='Message']",
    "[aria-label*='메시지']",
    "[aria-label*='프롬프트']"
  ];

  for (const selector of selectors) {
    const matches = page.locator(selector);
    const count = await matches.count().catch(() => 0);
    for (let i = count - 1; i >= 0; i--) {
      const locator = matches.nth(i);
      const visible = await locator.isVisible().catch(() => false);
      if (!visible) continue;
      const box = await locator.boundingBox().catch(() => null);
      const viewport = page.viewportSize() || { height: 900 };
      if (box && box.y >= viewport.height * 0.5) return locator;
    }
  }

  for (const selector of selectors) {
    const locator = page.locator(selector).last();
    if (await locator.count()) {
      const visible = await locator.isVisible().catch(() => false);
      if (visible) return locator;
    }
  }
  return null;
}

async function getPromptBoxText(box) {
  if (!box) return "";
  return await box.evaluate((element) => {
    if ("value" in element) return element.value || "";
    return element.innerText || element.textContent || "";
  }).catch(() => "");
}

async function typePromptText(prompt) {
  await page.keyboard.press(process.platform === "darwin" ? "Meta+A" : "Control+A");
  await page.keyboard.press("Backspace");
  await page.keyboard.insertText(prompt);
}

async function clickPromptCoordinateFallback() {
  return await page.evaluate(() => {
    const bottom = window.innerHeight * 0.5;
    const candidates = [...document.querySelectorAll("textarea, input, [contenteditable='true'], div, p")]
      .map((element) => {
        const rect = element.getBoundingClientRect();
        const text = `${element.getAttribute("placeholder") || ""} ${element.getAttribute("data-placeholder") || ""} ${element.getAttribute("aria-label") || ""} ${element.textContent || ""}`;
        return { element, rect, text };
      })
      .filter(({ rect, text }) => rect.width > 80 && rect.height > 20 && rect.y >= bottom && /무엇이든|물어보세요|message|prompt/i.test(text))
      .sort((a, b) => (b.rect.y + b.rect.height) - (a.rect.y + a.rect.height));
    const target = candidates[0];
    if (!target) return false;
    target.element.click();
    return true;
  }).catch(() => false);
}

async function fillPromptBox(box, prompt) {
  await box.click();
  await typePromptText(prompt);
  await page.waitForTimeout(typingSettleMs);
  let text = (await getPromptBoxText(box)).trim();
  if (!text) {
    await box.evaluate((element, value) => {
      if ("value" in element) element.value = value;
      else element.textContent = value;
      element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: value }));
    }, prompt);
    await page.waitForTimeout(typingSettleMs);
    text = (await getPromptBoxText(box)).trim();
  }
  if (!text) throw new Error("Prompt was not inserted into the input box.");
}

async function clickSend() {
  const candidates = [
    "button[data-testid='send-button']",
    "button[data-testid='composer-submit-button']",
    "button[aria-label*='Send prompt']",
    "button[aria-label*='프롬프트 보내기']",
    "button[aria-label*='메시지 보내기']",
    "button[aria-label*='보내기']",
    "button[aria-label*='Send']",
    "button[aria-label*='send']",
    "button[aria-label*='전송']",
    "button:has-text('Send')"
  ];

  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    await waitIfPaused();
    for (const selector of candidates) {
      const button = page.locator(selector).last();
      if (await button.count()) {
        const enabled = await button.isEnabled().catch(() => false);
        const visible = await button.isVisible().catch(() => false);
        if (enabled && visible) {
          await button.click();
          return true;
        }
      }
    }
    const fallbackClicked = await clickSendCoordinateFallback();
    if (fallbackClicked) return true;
    await page.waitForTimeout(sendSettleMs);
  }
  throw new Error("Send button was not active.");
}

async function clickSendCoordinateFallback() {
  return await page.evaluate(() => {
    const blocked = /attach|file|upload|voice|mic|microphone|dictate|audio|파일|첨부|업로드|음성|마이크/i;
    const candidates = [...document.querySelectorAll("button")]
      .map((button) => {
        const rect = button.getBoundingClientRect();
        const label = `${button.getAttribute("aria-label") || ""} ${button.textContent || ""} ${button.getAttribute("data-testid") || ""}`;
        return { button, rect, label };
      })
      .filter(({ button, rect, label }) =>
        !button.disabled &&
        rect.width > 18 &&
        rect.height > 18 &&
        rect.y >= window.innerHeight * 0.5 &&
        rect.x >= window.innerWidth * 0.45 &&
        !blocked.test(label)
      )
      .sort((a, b) => (b.rect.x + b.rect.y) - (a.rect.x + a.rect.y));
    const target = candidates[0]?.button;
    if (!target) return false;
    target.click();
    return true;
  }).catch(() => false);
}

async function countUserMessages() {
  return await page.locator("[data-message-author-role='user']").count().catch(() => 0);
}

async function waitForPromptSent(box, beforeUserMessages) {
  const start = Date.now();
  while (Date.now() - start < promptSentTimeoutMs) {
    const afterUserMessages = await countUserMessages();
    if (afterUserMessages > beforeUserMessages) return true;
    const text = box ? (await getPromptBoxText(box)).trim() : "";
    const stopVisible = await countStopButtons();
    if (!text && stopVisible > 0) return true;
    await page.waitForTimeout(promptSentPollMs);
  }
  return false;
}

async function submitPrompt(prompt) {
  let lastError = null;
  for (let attempt = 1; attempt <= promptSubmitMaxAttempts; attempt++) {
    const box = await findPromptBox();
    const beforeUserMessages = await countUserMessages();
    try {
      if (box) {
        await fillPromptBox(box, prompt);
      } else {
        const clicked = await clickPromptCoordinateFallback();
        if (!clicked) throw new Error("ChatGPT input box was not found.");
        await typePromptText(prompt);
      }
      await page.waitForTimeout(sendSettleMs);
      await clickSend();
      const sent = await waitForPromptSent(box, beforeUserMessages);
      if (sent) return true;
      lastError = new Error(`Prompt was not accepted within ${Math.floor(promptSentTimeoutMs / 1000)} seconds.`);
      log(`Prompt submit attempt ${attempt}/${promptSubmitMaxAttempts} did not continue. Retrying same prompt.`);
    } catch (error) {
      lastError = error;
      log(`Prompt submit attempt ${attempt}/${promptSubmitMaxAttempts} failed: ${error.message}`);
    }
    await page.waitForTimeout(1000);
  }
  throw lastError || new Error("No new user message appeared in ChatGPT.");
}

async function countStopButtons() {
  return await page.locator([
    "button:has-text('Stop')",
    "button:has-text('중지')",
    "button:has-text('중단')",
    "button[aria-label*='Stop']",
    "button[aria-label*='중지']",
    "button[aria-label*='중단']"
  ].join(", ")).count().catch(() => 0);
}

async function waitUntilGenerationSettles(index, total) {
  const start = Date.now();
  let stableChecks = 0;
  while (Date.now() - start < 240000) {
    await waitIfPaused();
    const elapsed = Math.floor((Date.now() - start) / 1000);
    progressMessage = `Prompt ${index}/${total} waiting for image generation (${elapsed}s)`;
    const stopVisible = await countStopButtons();
    if (!stopVisible) {
      stableChecks += 1;
      if (elapsed > 20 && stableChecks >= 3) return true;
      if (elapsed >= 90) {
        log(`Prompt ${index} waited 90s. Moving to next prompt.`);
        return true;
      }
    } else {
      stableChecks = 0;
    }
    await page.waitForTimeout(generationPollMs);
  }
  return false;
}

async function clickVisibleGeneratedImage() {
  return await page.evaluate(() => {
    const images = [...document.querySelectorAll("img")]
      .map((img, order) => {
        const rect = img.getBoundingClientRect();
        return { img, order, rect, area: rect.width * rect.height };
      })
      .filter(({ rect, area }) =>
        area > 40000 &&
        rect.width > 180 &&
        rect.height > 180 &&
        rect.bottom > 0 &&
        rect.right > 0 &&
        rect.top < window.innerHeight &&
        rect.left < window.innerWidth
      )
      .sort((a, b) => b.area - a.area || b.order - a.order);
    const target = images[0]?.img;
    if (!target) return false;
    target.scrollIntoView({ block: "center", inline: "center" });
    target.click();
    return true;
  });
}

async function openImageViewer() {
  const opened = await clickVisibleGeneratedImage();
  if (!opened) throw new Error("No generated image was visible to open.");
  await page.waitForTimeout(1200);
}

async function scrollViewerThumbnailsTop() {
  await page.evaluate(() => {
    const leftImages = [...document.querySelectorAll("img")]
      .map((img) => ({ img, rect: img.getBoundingClientRect() }))
      .filter(({ rect }) => rect.left < 220 && rect.width >= 24 && rect.height >= 24);
    for (const { img } of leftImages) {
      let parent = img.parentElement;
      while (parent) {
        const style = getComputedStyle(parent);
        if ((style.overflowY === "auto" || style.overflowY === "scroll") && parent.scrollHeight > parent.clientHeight) {
          parent.scrollTop = 0;
          return;
        }
        parent = parent.parentElement;
      }
    }
    window.scrollTo(0, 0);
  });
  await page.waitForTimeout(400);
}

async function getViewerThumbnailCount() {
  return await page.evaluate(() => {
    const seen = new Set();
    return [...document.querySelectorAll("img")]
      .map((img, order) => {
        const rect = img.getBoundingClientRect();
        const src = img.currentSrc || img.src || String(order);
        return { src, rect };
      })
      .filter(({ rect }) =>
        rect.left >= 40 &&
        rect.left < 190 &&
        rect.width >= 32 &&
        rect.width <= 120 &&
        rect.height >= 32 &&
        rect.height <= 120
      )
      .filter(({ src }) => {
        if (seen.has(src)) return false;
        seen.add(src);
        return true;
      }).length;
  });
}

async function clickViewerThumbnail(index) {
  return await page.evaluate((targetIndex) => {
    const seen = new Set();
    const thumbnails = [...document.querySelectorAll("img")]
      .map((img, order) => ({ img, order, rect: img.getBoundingClientRect(), src: img.currentSrc || img.src || String(order) }))
      .filter(({ rect }) =>
        rect.left >= 40 &&
        rect.left < 190 &&
        rect.width >= 32 &&
        rect.width <= 120 &&
        rect.height >= 32 &&
        rect.height <= 120
      )
      .sort((a, b) => a.rect.top - b.rect.top || a.order - b.order)
      .filter(({ src }) => {
        if (seen.has(src)) return false;
        seen.add(src);
        return true;
      });
    const target = thumbnails[targetIndex]?.img;
    if (!target) return false;
    target.scrollIntoView({ block: "center", inline: "center" });
    target.click();
    return true;
  }, index);
}

async function clickViewerDownloadButton() {
  const labeled = [
    "button[aria-label*='Download']",
    "button[aria-label*='download']",
    "button[aria-label*='다운로드']",
    "button[aria-label*='내려받기']",
    "button:has-text('Download')",
    "button:has-text('다운로드')"
  ];

  for (const selector of labeled) {
    const button = page.locator(selector).last();
    if (await button.count()) {
      const visible = await button.isVisible().catch(() => false);
      if (visible) {
        await button.click();
        return true;
      }
    }
  }

  return await page.evaluate(() => {
    const buttons = [...document.querySelectorAll("button")]
      .map((button) => {
        const rect = button.getBoundingClientRect();
        const label = `${button.getAttribute("aria-label") || ""} ${button.textContent || ""} ${button.title || ""}`;
        return { button, rect, label };
      })
      .filter(({ button, rect, label }) =>
        !button.disabled &&
        rect.width >= 24 &&
        rect.height >= 24 &&
        rect.top >= 0 &&
        rect.top < 90 &&
        rect.left > window.innerWidth * 0.82 &&
        !/share|공유|more|더보기|댓글|종횡비/i.test(label)
      )
      .sort((a, b) => a.rect.left - b.rect.left);
    const target = buttons[0]?.button;
    if (!target) return false;
    target.click();
    return true;
  });
}

function getDownloadExtension(download) {
  const name = download.suggestedFilename() || "";
  const ext = path.extname(name).replace(".", "").toLowerCase();
  return ext || "png";
}

async function selectFolderWithDialog() {
  const script = `
Add-Type -AssemblyName System.Windows.Forms
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = '이미지 다운로드 저장위치 선택'
$dialog.ShowNewFolderButton = $true
if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
  Write-Output $dialog.SelectedPath
}
`;
  const { stdout } = await execFileAsync("powershell.exe", ["-NoProfile", "-STA", "-Command", script], {
    windowsHide: false,
    timeout: 120000
  });
  return stdout.trim();
}

async function downloadGeneratedImages(projectDir, imageDownloadDir, runId) {
  const imageDir = imageDownloadDir ? normalizeLocalPath(imageDownloadDir) : path.join(projectDir, "downloaded_images");
  await fs.mkdir(imageDir, { recursive: true });
  const savedImages = [];

  await openImageViewer();
  await scrollViewerThumbnailsTop();
  const thumbnailCount = await getViewerThumbnailCount();
  if (!thumbnailCount) throw new Error("Image viewer thumbnails were not found.");

  for (let i = 0; i < thumbnailCount; i++) {
    await waitIfPaused();
    const clicked = await clickViewerThumbnail(i);
    if (!clicked) {
      log(`Image thumbnail ${i + 1}/${thumbnailCount} was not found.`);
      continue;
    }
    await page.waitForTimeout(700);

    let download = null;
    try {
      const downloadPromise = page.waitForEvent("download", { timeout: 20000 });
      const downloadClicked = await clickViewerDownloadButton();
      if (!downloadClicked) {
        downloadPromise.catch(() => {});
        log(`Download button was not found for image ${i + 1}/${thumbnailCount}.`);
        continue;
      }
      download = await downloadPromise;
    } catch (error) {
      log(`Image ${i + 1}/${thumbnailCount} download did not start: ${error.message}`);
      continue;
    }

    const ext = getDownloadExtension(download);
    const fileName = `chatgpt_image_${String(savedImages.length + 1).padStart(2, "0")}.${ext}`;
    const filePath = path.join(imageDir, fileName);
    await download.saveAs(filePath);
    const fileKey = `${runId}-${savedImages.length + 1}`;
    downloadedFileMap.set(fileKey, filePath);
    savedImages.push({
      fileName,
      path: filePath,
      url: `/api/downloaded-image/${fileKey}`
    });
    setProgress(`Downloaded image ${savedImages.length}/${thumbnailCount}.`);
  }

  return savedImages;
}

async function runAutomation({ projectName, prompts, targetUrl, options = {} }) {
  if (running) throw new Error("Already running.");
  running = true;
  paused = false;
  progressMessage = "Starting";
  currentLog = [];
  const runId = ++lastRunId;

  const safeProject = sanitize(projectName);
  const projectDir = path.join(outputRoot, safeProject);
  await fs.mkdir(projectDir, { recursive: true });
  await fs.writeFile(path.join(projectDir, "prompts.txt"), prompts.join("\n\n"), "utf8");

  try {
    const result = await ensureChatGptPage(targetUrl);
    if (!result.found) throw new Error("ChatGPT tab is not connected.");

    for (let i = 0; i < prompts.length; i++) {
      await waitIfPaused();
      setProgress(`Prompt ${i + 1}/${prompts.length} typing and sending`);
      await submitPrompt(prompts[i]);
      setProgress(`Prompt ${i + 1}/${prompts.length} waiting for image generation`);
      const settled = await waitUntilGenerationSettles(i + 1, prompts.length);
      if (!settled) log(`Prompt ${i + 1} timed out.`);
      setProgress(`Prompt ${i + 1}/${prompts.length} done.`);
      await page.waitForTimeout(promptGapMs);
    }

    let downloadedImages = [];
    if (options.downloadImages) {
      if (!options.imageDownloadDir) throw new Error("Image download folder is required.");
      setProgress("Image download enabled. Collecting generated images.");
      downloadedImages = await downloadGeneratedImages(projectDir, options.imageDownloadDir, runId);
      setProgress(`Downloaded ${downloadedImages.length} image(s).`);
    }

    lastRunResult = {
      id: runId,
      projectName: safeProject,
      projectDir,
      downloadedImages
    };
    setProgress(`Done. Output folder: ${projectDir}`);
    await fs.writeFile(path.join(projectDir, "log.txt"), currentLog.slice().reverse().join("\n"), "utf8");
  } finally {
    running = false;
    paused = false;
    progressMessage = "Idle";
  }
}

app.post("/api/check-chatgpt", async (req, res) => {
  try {
    const status = await getConnectionStatus(req.body?.targetUrl);
    res.json({ ok: true, ...status });
  } catch (error) {
    resetBrowserRefs();
    res.status(500).json({ ok: false, error: error.message });
  }
});

app.post("/api/run", async (req, res) => {
  const prompts = (req.body.prompts || []).map((value) => String(value || "").trim()).filter(Boolean).slice(0, maxPrompts);
  if (!prompts.length) return res.status(400).json({ ok: false, error: "No prompts." });
  runAutomation({
    projectName: req.body.projectName,
    prompts,
    targetUrl: req.body.targetUrl,
    options: req.body.options || {}
  }).catch((error) => log(`ERROR: ${error.message}`));
  res.json({ ok: true });
});

app.post("/api/select-image-download-folder", async (_req, res) => {
  try {
    const folderPath = await selectFolderWithDialog();
    if (!folderPath) return res.json({ ok: true, folderPath: "" });
    await fs.mkdir(folderPath, { recursive: true });
    res.json({ ok: true, folderPath });
  } catch (error) {
    res.status(500).json({ ok: false, error: error.message });
  }
});

app.post("/api/pause", (_req, res) => {
  if (!running) return res.json({ ok: true, running, paused });
  paused = true;
  progressMessage = "Paused";
  log("Paused.");
  res.json({ ok: true, running, paused });
});

app.post("/api/resume", (_req, res) => {
  if (!running) return res.json({ ok: true, running, paused });
  paused = false;
  progressMessage = "Resumed";
  log("Resumed.");
  res.json({ ok: true, running, paused });
});

app.get("/api/status", (_req, res) => {
  res.json({ running, paused, progressMessage, log: currentLog, lastRunResult });
});

app.get("/api/output-path", (_req, res) => {
  res.json({ outputRoot });
});

app.get("/api/downloaded-image/:key", async (req, res) => {
  const filePath = downloadedFileMap.get(req.params.key);
  if (!filePath) return res.status(404).send("Not found");
  res.sendFile(filePath);
});

const port = Number(process.env.PORT || 3737);
app.listen(port, () => {
  console.log(`Shorts Auto PC is running: http://localhost:${port}`);
});
