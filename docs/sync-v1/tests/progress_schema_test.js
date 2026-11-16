'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const Ajv2020 = require('ajv/dist/2020').default;
const addFormats = require('ajv-formats');
const root = path.resolve(__dirname, '..');
const schemaDir = path.join(root, 'schemas');
const fixtureDir = path.join(root, 'fixtures');
const json = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const ajv = new Ajv2020({allErrors:true,strict:true});
addFormats(ajv);
const schemas = new Map();
for(const name of fs.readdirSync(schemaDir).filter(n=>n.endsWith('.schema.json'))){const schema=json(path.join(schemaDir,name));schemas.set(name,ajv.compile(schema));}
function validate(schemaName,obj){const fn=schemas.get(schemaName);assert.ok(fn,`compiled schema ${schemaName}`);const ok=fn(obj);validate.lastErrors=fn.errors;return ok;}
const mappings=[
  [/^catalog.*\.valid\.json$/,'catalog.schema.json'],
  [/^chapters.*\.valid\.json$/,'chapters.schema.json'],
  [/^progress-event\.valid\.json$/,'progress-event.schema.json'],
  [/^progress-state.*\.valid\.json$/,'progress-state.schema.json'],
  [/^snapshot\.valid\.json$/,'snapshot.schema.json'],
  [/^acknowledgement-.*\.valid\.json$/,'acknowledgement.schema.json'],
  [/^binding-request.*\.valid\.json$/,'binding-request.schema.json'],
  [/^device-registration\.valid\.json$/,'device-registration.schema.json'],
  [/^device-state-.*\.valid\.json$/,'device-state.schema.json'],
  [/^reading-time-event\.valid\.json$/,'reading-time-event.schema.json'],
  [/^normalization-v1-vectors\.valid\.json$/,'normalization-vectors.schema.json'],
  [/^create-mode-vectors\.valid\.json$/,'create-mode-vectors.schema.json'],
  [/^snapshot-selection-vectors\.valid\.json$/,'snapshot-selection-vectors.schema.json'],
  [/^progress-semantics-vectors\.valid\.json$/,'progress-semantics-vectors.schema.json']
];

test('all Draft 2020-12 schemas compile in strict mode',()=>{assert.ok(schemas.size>=14);});
test('every positive protocol fixture validates against its Draft 2020-12 schema',()=>{
  const used=new Set();
  for(const name of fs.readdirSync(fixtureDir).filter(n=>n.endsWith('.valid.json'))){
    const rule=mappings.find(([re])=>re.test(name));
    assert.ok(rule,`fixture mapping missing for ${name}`); used.add(name);
    const obj=json(path.join(fixtureDir,name)); const ok=validate(rule[1],obj);
    assert.equal(ok,true,`${name}: ${JSON.stringify(validate.lastErrors||[])}`);
  }
  assert.ok(used.size>0);
});

test('catalog baseline field is optional for legacy, with only three online enum values',()=>{
  const old=json(path.join(fixtureDir,'catalog-legacy-unknown.valid.json'));
  assert.equal(validate('catalog.schema.json',old),true);
  const bad=structuredClone(old);bad.works[0].progress_baseline_status='legacy_unknown';
  assert.equal(validate('catalog.schema.json',bad),false);
  const newCatalog=json(path.join(fixtureDir,'catalog.valid.json'));
  for(const status of ['zero_confirmed','available','unavailable']){const c=structuredClone(newCatalog);c.works[0].progress_baseline_status=status;assert.equal(validate('catalog.schema.json',c),true,status);}
});

test('progress schemas require integer ppm and positive projection revisions',()=>{
  const event=json(path.join(fixtureDir,'progress-event.valid.json'));
  assert.equal(validate('progress-event.schema.json',event),true);
  const oldField=structuredClone(event);delete oldField.chapter_fraction_ppm;oldField.chapter_fraction=0.5;
  assert.equal(validate('progress-event.schema.json',oldField),false,'old floating field rejected');
  const fraction=structuredClone(event);fraction.chapter_fraction_ppm=666667.5;assert.equal(validate('progress-event.schema.json',fraction),false);
  const outOfRange=structuredClone(event);outOfRange.chapter_fraction_ppm=1000001;assert.equal(validate('progress-event.schema.json',outOfRange),false);
  const state=json(path.join(fixtureDir,'progress-state.valid.json'));
  for(const key of ['toc_revision','progress_revision']){const bad=structuredClone(state);bad[key]=0;assert.equal(validate('progress-state.schema.json',bad),false,`${key}=0 rejected`);}
  const tooLargeRevision=structuredClone(state);tooLargeRevision.progress_revision=9007199254740992;assert.equal(validate('progress-state.schema.json',tooLargeRevision),false,'unsafe revision rejected');
  const badSnapshot=json(path.join(fixtureDir,'snapshot.valid.json'));badSnapshot.progress_states[0].progress_revision=0;assert.equal(validate('snapshot.schema.json',badSnapshot),false);
});

test('chapter_only schema forbids character-level fields',()=>{
  const event=json(path.join(fixtureDir,'progress-event.valid.json'));
  const chapter={...event,visible_char_offset:null,visible_char_count:null,chapter_text_fingerprint:null,reported_locator_confidence:'chapter',chapter_fraction_ppm:666667};
  assert.equal(validate('progress-event.schema.json',chapter),true);
  for(const patch of [{visible_char_offset:0},{visible_char_count:1},{chapter_text_fingerprint:'a'.repeat(64)},{reported_locator_confidence:'estimated'}]){
    assert.equal(validate('progress-event.schema.json',{...chapter,...patch}),false,JSON.stringify(patch));
  }
});
