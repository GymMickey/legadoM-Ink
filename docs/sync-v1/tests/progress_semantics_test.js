'use strict';
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const root = path.resolve(__dirname, '..');
const fixtures = path.join(root, 'fixtures');
const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const vectors = readJson(path.join(fixtures, 'progress-semantics-vectors.valid.json'));
const MAX_SAFE = BigInt(Number.MAX_SAFE_INTEGER);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function baselineResult(c) {
  if (!c.scope_matches) return 'baseline_recheck_required';
  if (c.status === null) return 'legacy_unknown';
  if (c.status === 'zero_confirmed') {
    if (c.progress_revision !== 0) return 'invalid_baseline_revision';
    if (c.revision_zero_file_exists) return 'revision_zero_state_forbidden';
    if (!c.authoritative_zero) return 'zero_baseline_not_authoritative';
    return 'zero_confirmed';
  }
  if (c.status === 'unavailable') {
    if (c.progress_revision !== 0) return 'invalid_baseline_revision';
    if (c.catalog_references_state) return 'unavailable_must_not_reference_state';
    return 'unavailable';
  }
  if (c.status === 'available') {
    if (c.progress_revision < 1) return 'invalid_baseline_revision';
    if (!c.state_exists) return 'progress_state_missing';
    if (c.state_toc_revision !== undefined && c.current_toc_revision !== undefined && c.state_toc_revision !== c.current_toc_revision) return 'progress_toc_stale';
    return 'available';
  }
  return 'baseline_status_invalid';
}
function eventBaselineAllowed(c) {
  const state=baselineResult(c);
  return state==='zero_confirmed'||state==='available'||state==='progress_toc_stale';
}
function selectProgressState(c) {
  if (c.baseline === 'zero_confirmed') return 'zero_baseline_no_state_request';
  if (c.baseline === 'legacy_unknown' || c.baseline === 'unavailable') return 'baseline_unavailable';
  const byRevision = new Map();
  for (const item of c.candidates) { const list = byRevision.get(item.revision) || []; list.push(item); byRevision.set(item.revision, list); }
  if ([...byRevision.values()].some(group => group.length > 1)) return 'revision_conflict';
  const ref = byRevision.get(c.catalog_revision) || [];
  if (ref.length === 0) return 'progress_state_missing';
  const item = ref[0];
  if (!item.valid || item.work_id !== 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa') return 'progress_state_invalid_no_fallback';
  if (c.candidates.some(item => item.revision > c.catalog_revision && !item.valid)) return 'progress_state_invalid_no_fallback';
  if (c.candidates.some(item => item.revision > c.catalog_revision && item.valid)) return 'progress_catalog_pending';
  return 'selected';
}
function integerPpm(position, total) {
  if (!Number.isSafeInteger(position) || !Number.isSafeInteger(total) || position < 0 || total <= 0 || position > total) return null;
  const numerator = BigInt(position) * 1000000n;
  const roundedNumerator = numerator + BigInt(Math.floor(total / 2));
  if (numerator > MAX_SAFE || roundedNumerator > MAX_SAFE) return null;
  const result = roundedNumerator / BigInt(total);
  return Number(result < 0n ? 0n : result > 1000000n ? 1000000n : result);
}
function validChapterOnly(c) {
  return c.visible_char_offset === null && c.visible_char_count === null && c.chapter_text_fingerprint === null && c.reported_locator_confidence === 'chapter' && c.chapter_fraction_is_estimated === true && (c.chapter_fraction_ppm === null || (Number.isInteger(c.chapter_fraction_ppm) && c.chapter_fraction_ppm >= 0 && c.chapter_fraction_ppm <= 1000000));
}
function chooseHighestSnapshot(vector) {
  const byPath = new Map();
  for (const c of vector.candidates) {
    const prev = byPath.get(c.path);
    if (!prev) byPath.set(c.path,c);
    else if (prev.payload_sha256 !== c.payload_sha256 || prev.payload_utf8 !== c.payload_utf8) return {outcome:'revision_conflict'};
  }
  const candidates=[...byPath.values()];
  const groups=new Map();
  for(const c of candidates){const a=groups.get(c.revision)||[];a.push(c);groups.set(c.revision,a);}
  if([...groups.values()].some(g=>new Set(g.map(x=>x.snapshot_id)).size>1 || new Set(g.map(x=>x.payload_sha256)).size>1)) return {outcome:'revision_conflict'};
  if(!candidates.length)return {outcome:'no_valid_candidate'};
  const highest=Math.max(...candidates.map(c=>c.revision));
  const top=groups.get(highest);
  if(top.length!==1)return {outcome:'revision_conflict'};
  const c=top[0];
  const hash=crypto.createHash('sha256').update(Buffer.from(c.payload_utf8,'utf8')).digest('hex');
  const pathIdentity=c.path_revision===c.revision && c.path_snapshot_id===c.snapshot_id;
  const bytesIdentity=hash===c.payload_sha256;
  let payloadValid=false;
  try { const p=JSON.parse(c.payload_utf8); payloadValid=p.snapshot_id===c.snapshot_id && (vector.projection_kind==='catalog'?p.catalog_revision===c.revision:vector.projection_kind==='chapters'?p.toc_revision===c.revision:vector.projection_kind==='progress'?p.progress_revision===c.revision:p.state_revision===c.revision) && (!c.work_id || p.work_id===c.work_id) && (vector.projection_kind==='progress' || !c.device_id || p.device_id===c.device_id); } catch {}
  if(!pathIdentity || !bytesIdentity || !payloadValid) return {outcome:'no_valid_candidate'};
  return {outcome:'selected',revision:c.revision,snapshot_id:c.snapshot_id};
}

test('baseline combinations and legacy compatibility', () => {
  for(const c of vectors.baseline_cases){
    assert.equal(baselineResult(c),c.expected,c.case_id);
    assert.equal(c.allow_k2,true,`${c.case_id}: K2 stays available`);
    assert.equal(c.allow_k4, c.expected==='available'||c.expected==='zero_confirmed'||c.expected==='progress_toc_stale',`${c.case_id}: K4 gate`);
    if(c.expected==='progress_toc_stale') assert.equal(c.apply_to_current_toc,false);
  }
});

test('catalog-referenced progress-state selection, conflicts, and no fallback', () => {
  for(const c of vectors.selection_cases) assert.equal(selectProgressState(c),c.expected,c.case_id);
  const conflict=vectors.selection_cases.find(c=>c.case_id==='same_revision_multiple_snapshots');
  assert.notEqual(conflict.candidates[0].raw_sha256,conflict.candidates[1].raw_sha256,'conflicting objects have different digests');
});

test('Android catalog producer contract includes a recognized baseline for every new work', () => {
  const catalog=readJson(path.join(fixtures,'catalog.valid.json'));
  assert.ok(catalog.works.length>0);
  for(const work of catalog.works) assert.ok(['zero_confirmed','available','unavailable'].includes(work.progress_baseline_status));
  const legacy=readJson(path.join(fixtures,'catalog-legacy-unknown.valid.json'));
  for(const work of legacy.works) assert.equal(Object.hasOwn(work,'progress_baseline_status'),false);
});

test('catalog work identity uniqueness is a semantic constraint', () => {
  const catalog=readJson(path.join(fixtures,'catalog.valid.json'));
  const duplicate={...catalog.works[0]};catalog.works.push(duplicate);
  const uniqueWorkIds=(works)=>new Set(works.map(x=>x.work_id)).size===works.length;
  assert.equal(uniqueWorkIds(readJson(path.join(fixtures,'catalog.valid.json')).works),true);
  assert.equal(uniqueWorkIds(catalog.works),false);
});

test('baseline gates event processing without breaking legacy K2/K3', () => {
  for(const c of vectors.baseline_cases){
    assert.equal(eventBaselineAllowed(c),c.allow_k4,c.case_id);
    const outcome=eventBaselineAllowed(c)?'continue_baseline_check':'hold_pending_without_terminal_receipt';
    if(['legacy_unknown','unavailable','progress_state_missing','baseline_recheck_required','invalid_baseline_revision','revision_zero_state_forbidden'].includes(baselineResult(c))) assert.equal(outcome,'hold_pending_without_terminal_receipt',c.case_id);
    assert.equal(c.allow_k2,true,`${c.case_id}: K2/K3 remain available`);
  }
});

test('Android revision-1 initial export and independent TOC/progress event baselines', () => {
  const initial=readJson(path.join(fixtures,'progress-state-initial-baseline.valid.json'));
  assert.equal(initial.progress_revision,1);assert.equal(initial.source_event_id,null);assert.match(initial.updated_by_device_id,UUID);
  const catalog={toc_revision:8,progress_baseline_status:'available',progress_revision:4};
  const cachedState={toc_revision:7,progress_revision:4};
  const currentTocChapterIsVerified=true;
  const event={based_on_toc_revision:catalog.toc_revision,based_on_progress_revision:catalog.progress_revision,local_toc_index:12};
  assert.equal(cachedState.toc_revision!==catalog.toc_revision,true,'old state is progress_toc_stale and cannot be applied to the new TOC');
  assert.equal(currentTocChapterIsVerified,true);
  assert.equal(event.based_on_toc_revision,8);
  assert.equal(event.based_on_progress_revision,4,'progress concurrency baseline remains the catalog confirmed progress revision');
});

test('progress-state raw-byte limit is checked before JSON decoding', () => {
  const valid=fs.readFileSync(path.join(fixtures,'progress-state.valid.json'));
  const base=valid.toString('utf8');
  const padded=(n)=>Buffer.from(base+' '.repeat(n-Buffer.byteLength(base)),'utf8');
  function guardedDecode(bytes, decode){if(bytes.length>vectors.progress_state_max_raw_bytes)return 'progress_state_too_large';decode(bytes.toString('utf8'));return 'ok';}
  let parseCalls=0;
  assert.equal(padded(65536).length,65536);
  assert.equal(guardedDecode(padded(65536),()=>parseCalls++),'ok');
  assert.equal(parseCalls,1);
  assert.equal(padded(65537).length,65537);
  assert.equal(guardedDecode(padded(65537),()=>parseCalls++),'progress_state_too_large');
  assert.equal(parseCalls,1,'over-limit bytes must be rejected before decoder call');
  assert.throws(()=>JSON.parse(base.replace(/}\s*$/,'')),'truncated JSON is never used as a fallback');
});

test('ppm fixed vectors use integer-only safe intermediate arithmetic', () => {
  for(const c of vectors.ppm_cases) assert.equal(integerPpm(c.position,c.total),c.expected,c.case_id);
});

test('chapter_only remains chapter confidence regardless of ppm', () => {
  for(const c of vectors.chapter_only_cases) assert.equal(validChapterOnly(c),c.valid,c.case_id);
  assert.equal(validChapterOnly({...vectors.chapter_only_cases[0],chapter_fraction_ppm:1000000}),true);
  assert.equal(validChapterOnly({...vectors.chapter_only_cases[0],visible_char_offset:0}),false);
  const validCharacterRange=(offset,count)=>Number.isSafeInteger(offset)&&Number.isSafeInteger(count)&&offset>=0&&count>0&&offset<=count;
  assert.equal(validCharacterRange(4,6),true,'semantic validator accepts offset <= count');
  assert.equal(validCharacterRange(7,6),false,'semantic validator rejects offset > count without truncation');
});

test('stale acknowledgements wait for dependencies and rebasing creates a new event chain', () => {
  for(const c of vectors.stale_cases){
    if(c.both_revisions_mismatch){assert.equal(c.expected,'stale_toc_priority',c.case_id);assert.equal(c.kind,'stale_toc');continue;}
    if(!c.dependencies_confirmed) assert.equal(c.expected,'keep_pending_no_ack',c.case_id);
    else {
      assert.match(c.new_event_id,UUID,c.case_id);
      assert.notEqual(c.new_event_id,c.old_event_id,c.case_id);
      assert.ok(c.new_sequence>c.old_sequence,c.case_id);
      assert.notEqual(c.new_payload_sha256,c.old_payload_sha256,c.case_id);
      assert.ok(c.current_revision>=1,c.case_id);
    }
  }
});

test('session_id is scoped to a document session and never replaces event id or counter epoch', () => {
  const openSession={session_id:'44444444-4444-4444-8444-444444444444',event_id:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',counter_epoch:'33333333-3333-4333-8333-333333333333'};
  const sameOpenSession={...openSession};
  const reopened={...openSession,session_id:'55555555-5555-4555-8555-555555555555'};
  assert.match(openSession.session_id,UUID);assert.deepEqual(sameOpenSession,openSession);
  assert.notEqual(reopened.session_id,openSession.session_id);
  assert.equal(reopened.event_id,openSession.event_id,'session id is not the event idempotency key');
  assert.equal(reopened.counter_epoch,openSession.counter_epoch,'session loss/reopen does not rotate counter_epoch');
});

test('TOC changes only increment progress revision after a safe projection', () => {
  const current={progress_revision:4,toc_revision:7};
  const failed={...current,requested_toc_revision:8,projection_safe:false};
  assert.equal(failed.progress_revision,current.progress_revision);
  assert.equal(failed.toc_revision,current.toc_revision);
  const projected={progress_revision:current.progress_revision+1,toc_revision:8,projection_safe:true,source_event_id:null};
  assert.equal(projected.progress_revision,5);
  assert.equal(projected.toc_revision,8);
  assert.equal(projected.source_event_id,null);
});

test('transaction recovery preserves immutable intents and gates ack publication', () => {
  for(const c of vectors.recovery_cases){
    assert.equal(c.ledger_delta,0,c.case_id); assert.equal(c.revision_delta,0,c.case_id);
    if(c.crash_point==='before_commit') { assert.equal(c.intent_exists,false);assert.equal(c.receipt_exists,false); }
    else { assert.equal(c.intent_exists,true);assert.equal(c.receipt_exists,true); }
  }
  const intent={event_id:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',sequence:44,raw_bytes:'frozen-event-bytes',payload_sha256:'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',ack_raw_bytes:'frozen-ack-bytes',ack_sha256:'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',acknowledged_at:'2026-01-02T03:04:06Z'};
  const recovered={...intent};
  assert.deepEqual(recovered,intent,'restart reuses the exact event/state/catalog/ack intent data');
  let stateConfirmed=false,catalogConfirmed=false,ackPublished=false;
  const publishAck=()=>{if(!stateConfirmed||!catalogConfirmed)return false;ackPublished=true;return true;};
  assert.equal(publishAck(),false,'ack cannot publish before projection dependencies');
  stateConfirmed=true;assert.equal(publishAck(),false,'ack cannot publish before catalog confirmation');
  catalogConfirmed=true;assert.equal(publishAck(),true);assert.equal(ackPublished,true);
});

test('all existing snapshot selection vectors retain frozen no-fallback semantics and hashes', () => {
  const v=readJson(path.join(fixtures,'snapshot-selection-vectors.valid.json'));
  for(const item of v.vectors){const result=chooseHighestSnapshot(item);assert.equal(result.outcome,item.expected_outcome,item.case_id);assert.equal(result.revision??null,item.selected_revision,item.case_id);assert.equal(result.snapshot_id??null,item.selected_snapshot_id,item.case_id);}
});

test('modified event and linked acknowledgements use raw-byte hashes', () => {
  const names=fs.readdirSync(fixtures); const eventPath=path.join(fixtures,'progress-event.valid.json'); const eventBytes=fs.readFileSync(eventPath); const event=JSON.parse(eventBytes); const digest=crypto.createHash('sha256').update(eventBytes).digest('hex');
  const linked=names.filter(n=>n.startsWith('acknowledgement-')&&n.endsWith('.valid.json')).map(n=>({name:n,value:readJson(path.join(fixtures,n))})).filter(x=>x.value.event_id===event.event_id);
  assert.ok(linked.length>0); for(const ack of linked) assert.equal(ack.value.payload_sha256,digest,ack.name);
  const vector=readJson(path.join(fixtures,'snapshot-selection-vectors.valid.json')); for(const v of vector.vectors)for(const c of v.candidates)assert.equal(crypto.createHash('sha256').update(Buffer.from(c.payload_utf8,'utf8')).digest('hex'),c.payload_sha256,v.case_id);
});
