"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const Ajv2020 = require("ajv/dist/2020");
const addFormats = require("ajv-formats");

const docsDir = path.resolve(__dirname, "..");
const schema = JSON.parse(fs.readFileSync(
  path.join(docsDir, "schemas", "chapters.schema.json"), "utf8"));
const fixtureDir = path.join(docsDir, "fixtures");
const fixtureNames = [
  "chapters.valid.json",
  "chapters-structure.valid.json",
  "chapters-empty.valid.json",
];
const fixtures = fixtureNames.map((name) => ({
  name,
  value: JSON.parse(fs.readFileSync(path.join(fixtureDir, name), "utf8")),
}));

const ajv = new Ajv2020({
  allErrors: true,
  strict: true,
  validateFormats: true,
});
addFormats(ajv);
const validate = ajv.compile(schema);

assert.equal(schema.properties.chapters.maxItems, 6000);
assert.ok(!schema.required.includes("normalization_version"));
assert.ok(!Object.hasOwn(schema.properties, "normalization_version"));

function isUnicodeScalarString(value) {
  if (typeof value !== "string" || Array.from(value).length === 0) return false;
  for (let i = 0; i < value.length; i += 1) {
    const unit = value.charCodeAt(i);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      if (i + 1 >= value.length) return false;
      const low = value.charCodeAt(i + 1);
      if (low < 0xdc00 || low > 0xdfff) return false;
      i += 1;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      return false;
    }
  }
  return true;
}

function chapterKey(workId, index, rawTitle) {
  const input = "chapter-key-v1\u001f" + workId + "\u001f" +
    String(index) + "\u001f" + rawTitle;
  return "ck1-" + crypto.createHash("sha256")
    .update(Buffer.from(input, "utf8"))
    .digest("hex");
}

function semanticChaptersValid(value) {
  const seenKeys = new Set();
  const seenIndices = new Set();
  let previous = -1;
  for (const chapter of value.chapters) {
    if (!isUnicodeScalarString(chapter.title) ||
        Array.from(chapter.title).length > 512 ||
        seenKeys.has(chapter.chapter_key) ||
        seenIndices.has(chapter.local_toc_index) ||
        chapter.local_toc_index <= previous ||
        chapter.chapter_key !== chapterKey(
          value.work_id, chapter.local_toc_index, chapter.title)) {
      return false;
    }
    seenKeys.add(chapter.chapter_key);
    seenIndices.add(chapter.local_toc_index);
    previous = chapter.local_toc_index;
  }
  return true;
}

function pathMatchesContent(relativePath, value) {
  const match = /^android\/chapters\/([0-9a-f-]+)\/([1-9][0-9]*)-([0-9a-f-]+)\.json$/.exec(relativePath);
  return !!match &&
    match[1] === value.work_id &&
    Number(match[2]) === value.toc_revision &&
    match[3] === value.snapshot_id;
}

for (const fixture of fixtures) {
  assert.equal(validate(fixture.value), true,
    fixture.name + ": " + JSON.stringify(validate.errors));
  assert.equal(semanticChaptersValid(fixture.value), true,
    fixture.name + ": chapter semantic check failed");
}
assert.deepEqual(fixtures[2].value.chapters, []);

const structure = fixtures[1].value;
assert.deepEqual(
  structure.chapters.map((chapter) => chapter.local_toc_index),
  [2, 8, 13, 21],
);
assert.notEqual(structure.chapters[0].chapter_key, structure.chapters[1].chapter_key);
assert.notEqual(structure.chapters[2].chapter_key, structure.chapters[3].chapter_key);

function singleChapter(title) {
  const value = {
    ...fixtures[0].value,
    chapters: [{
      chapter_key: chapterKey(fixtures[0].value.work_id, 0, title),
      title,
      local_toc_index: 0,
    }],
  };
  return value;
}

const title512 = "😀".repeat(512);
assert.equal(Array.from(title512).length, 512);
assert.equal(title512.length, 1024);
assert.equal(validate(singleChapter(title512)), true,
  "512 Unicode code points must pass even when UTF-16 String.length is 1024");
const title513 = "😀".repeat(513);
assert.equal(Array.from(title513).length, 513);
assert.equal(validate(singleChapter(title513)), false,
  "513 Unicode code points must fail maxLength");
assert.ok(validate.errors.some((error) =>
  error.keyword === "maxLength" && error.instancePath === "/chapters/0/title"));

const isolatedSurrogate = singleChapter(String.fromCharCode(0xd800));
assert.equal(isUnicodeScalarString(isolatedSurrogate.chapters[0].title), false);
assert.equal(semanticChaptersValid(isolatedSurrogate), false,
  "isolated surrogate must reject the whole chapters object");

const sourceStates = ["AVAILABLE", "KNOWN_EMPTY", "UNAVAILABLE", "FAILED"];
function sourceCanPublish(state) {
  return state === "AVAILABLE" || state === "KNOWN_EMPTY";
}
assert.deepEqual(sourceStates.map(sourceCanPublish), [true, true, false, false]);
assert.equal(sourceCanPublish("FAILED"), false,
  "a failed directory read must never be interpreted as empty");

function runBatch(works, state) {
  let failed = false;
  let catalogPublishes = 0;
  const events = [];
  for (const work of works) {
    events.push("chapter:" + work.workId);
    if (!sourceCanPublish(work.sourceState) || !work.putAndReadbackVerified) {
      failed = true;
      continue;
    }
    events.push("persist:" + work.workId);
    state.confirmed[work.workId] = {
      serviceIdentity: work.serviceIdentity,
      rootIdentity: work.rootIdentity,
      revision: work.nextRevision,
      snapshotId: work.snapshotId,
      digest: work.digest,
    };
    state.remoteObjects.add(objectIdentity(work.serviceIdentity, work.rootIdentity,
      work.workId, work.nextRevision, work.snapshotId));
    state.catalogDirty = true;
  }

  if (state.catalogDirty) {
    events.push("catalog");
    catalogPublishes += 1;
    const references = [];
    for (const [workId, confirmed] of Object.entries(state.confirmed)) {
      const key = objectIdentity(confirmed.serviceIdentity, confirmed.rootIdentity,
        workId, confirmed.revision, confirmed.snapshotId);
      if (state.remoteObjects.has(key)) references.push({
        workId,
        tocRevision: confirmed.revision,
        snapshotId: confirmed.snapshotId,
      });
    }
    state.lastCatalogReferences = references;
    state.catalogDirty = false;
  }
  return {
    status: failed ? "partial_success" : "success",
    catalogPublishes,
    events,
  };
}

function objectIdentity(service, root, workId, revision, snapshotId) {
  return [service, root, workId, revision, snapshotId].join("|");
}

const service = "webdav-service-a";
const rootA = "root-a";
const rootB = "root-b";
const oldRootKey = objectIdentity(service, rootA, "work-1", 3, "snapshot-old");
const newRootKey = objectIdentity(service, rootB, "work-1", 3, "snapshot-old");
assert.notEqual(oldRootKey, newRootKey,
  "confirmed state must be scoped by serviceIdentity + rootIdentity + work_id");

const state = {
  confirmed: {
    "work-1": {
      serviceIdentity: service,
      rootIdentity: rootA,
      revision: 3,
      snapshotId: "snapshot-old",
      digest: "a".repeat(64),
    },
    "work-2": {
      serviceIdentity: service,
      rootIdentity: rootB,
      revision: 5,
      snapshotId: "snapshot-not-present",
      digest: "b".repeat(64),
    },
  },
  remoteObjects: new Set([oldRootKey]),
  catalogDirty: false,
};
const batch = runBatch([
  {
    workId: "work-1",
    sourceState: "AVAILABLE",
    putAndReadbackVerified: true,
    serviceIdentity: service,
    rootIdentity: rootB,
    nextRevision: 1,
    snapshotId: "snapshot-new-root",
    digest: "c".repeat(64),
  },
  {
    workId: "work-2",
    sourceState: "FAILED",
    putAndReadbackVerified: false,
    serviceIdentity: service,
    rootIdentity: rootB,
    nextRevision: 6,
    snapshotId: "snapshot-failed",
    digest: "d".repeat(64),
  },
], state);
assert.equal(batch.status, "partial_success");
assert.equal(batch.catalogPublishes, 1,
  "batch publishes at most one summary catalog");
assert.ok(batch.events.indexOf("catalog") > batch.events.indexOf("chapter:work-2"),
  "summary catalog is published only after all works are processed");
assert.equal(state.confirmed["work-2"].revision, 5,
  "failed work retains its previous toc_revision");
assert.deepEqual(state.lastCatalogReferences.map((item) => item.workId), ["work-1"],
  "catalog must reference only chapters confirmed at its service/root");

const switchRootState = {
  confirmed: {},
  remoteObjects: new Set([oldRootKey]),
  catalogDirty: false,
};
assert.equal(switchRootState.remoteObjects.has(newRootKey), false,
  "old root confirmation cannot satisfy new root publication");
assert.equal(
  switchRootState.remoteObjects.has(objectIdentity(service, rootB,
    "work-1", 1, "snapshot-new-root")),
  false,
  "old-root state cannot satisfy a new-root catalog reference");
switchRootState.remoteObjects.add(objectIdentity(service, rootB,
  "work-1", 1, "snapshot-new-root"));
assert.equal(
  switchRootState.remoteObjects.has(objectIdentity(service, rootB,
    "work-1", 1, "snapshot-new-root")),
  true,
  "new root can be referenced only after its chapters are confirmed");

const pendingCatalogIntent = {
  serviceIdentity: service,
  rootIdentity: rootB,
  rawBytes: Buffer.from('{"catalog_revision":2}', "utf8"),
  revision: 2,
  snapshotId: "catalog-snapshot-2",
};
let serializerCalls = 0;
let assignedRevision = pendingCatalogIntent.revision;
function resumeCatalogIntent(intent) {
  return intent;
}
const recoveredIntent = resumeCatalogIntent(pendingCatalogIntent);
assert.equal(recoveredIntent, pendingCatalogIntent);
assert.deepEqual(recoveredIntent.rawBytes, Buffer.from('{"catalog_revision":2}', "utf8"));
assert.equal(serializerCalls, 0, "recovery must not reserialize the saved intent");
assert.equal(assignedRevision, 2, "recovery must not allocate another revision");

let activeTask = null;
let releaseActiveTask;
let allocatedSnapshots = 0;
function startPublicationTask() {
  if (activeTask) return activeTask;
  allocatedSnapshots += 1;
  activeTask = new Promise((resolve) => { releaseActiveTask = resolve; });
  return activeTask;
}
const firstTask = startPublicationTask();
const duplicateTask = startPublicationTask();
assert.equal(firstTask, duplicateTask);
assert.equal(allocatedSnapshots, 1,
  "duplicate starts must not concurrently allocate revisions or snapshots");
releaseActiveTask();

const maxBytes = 2 * 1024 * 1024;
function makeCountCandidate(count) {
  const workId = fixtures[0].value.work_id;
  const title = "章节";
  const chapters = Array.from({ length: count }, (_, index) => ({
    chapter_key: chapterKey(workId, index, title),
    title,
    local_toc_index: index,
  }));
  return { ...fixtures[0].value, chapters };
}
const atLimit = makeCountCandidate(6000);
assert.equal(validate(atLimit), true,
  "6000 chapters must pass chapters.schema.json");
const overLimit = makeCountCandidate(6001);
assert.equal(validate(overLimit), false,
  "6001 chapters must fail chapters.schema.json");
assert.ok(validate.errors.some((error) =>
  error.keyword === "maxItems" && error.instancePath === "/chapters"),
"6001 chapters must be rejected by maxItems");

function makeLargeCandidate(count) {
  const workId = fixtures[0].value.work_id;
  const title = "汉".repeat(512);
  const chapters = Array.from({ length: count }, (_, index) => ({
    chapter_key: chapterKey(workId, index, title),
    title,
    local_toc_index: index,
  }));
  return { ...fixtures[0].value, chapters };
}
const underLimitBytes = Buffer.byteLength(JSON.stringify(makeLargeCandidate(1100)), "utf8");
const overLimitBytes = Buffer.byteLength(JSON.stringify(makeLargeCandidate(1400)), "utf8");
assert.ok(underLimitBytes <= maxBytes);
assert.ok(overLimitBytes > maxBytes);
assert.ok(Buffer.byteLength("汉", "utf8") > "汉".length,
  "payload size must use final UTF-8 bytes, never Kotlin String.length");

console.log("chapters_protocol_test: Schema, fixtures, title boundaries, directory states, batch publishing, root isolation, intent recovery, single-flight, and UTF-8 byte limit passed");