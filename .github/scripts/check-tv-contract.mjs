#!/usr/bin/env node
// Contract between the TV interface and the original Xtra, checked WITHOUT compiling.
// The compiler already catches renamed classes and functions; this catches what it cannot:
// the TV settings reference upstream preferences by string key and upstream navigation actions by id, so when the
// original renames or removes one, the TV settings silently lose that option (or open the wrong screen).
//
//   node .github/scripts/check-tv-contract.mjs      (run from the repository root)
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const res = 'app/src/main/res'
const src = 'app/src/main/java/com/github/andreyasadchy/xtra'
const read = (p) => readFileSync(p, 'utf8')
const errors = []

// constants "const val NAME = "value"" of a Kotlin object
const constants = (file) => Object.fromEntries(
  [...read(file).matchAll(/const val (\w+)\s*=\s*"([^"]*)"/g)].map((m) => [m[1], m[2]]),
)
const upstreamC = constants(join(src, 'util/C.kt'))
const tvPrefs = constants(join(src, 'ui/tv/TvPrefs.kt'))
const resolveKey = (token) => {
  if (token.startsWith('"')) return token.slice(1, -1)
  const [owner, name] = token.split('.')
  const value = (owner === 'C' ? upstreamC : owner === 'TvPrefs' ? tvPrefs : {})[name]
  if (value === undefined) errors.push(`Cannot resolve the settings key ${token}`)
  return value
}

// android:key values declared by each preference XML
const xmlKeys = {}
for (const f of readdirSync(join(res, 'xml'))) {
  if (f.endsWith('_preferences.xml')) {
    xmlKeys[f.replace('.xml', '')] = new Set([...read(join(res, 'xml', f)).matchAll(/android:key="([^"]+)"/g)].map((m) => m[1]))
  }
}

// 1. every preference the TV settings take from an upstream XML still exists in that XML
const modelFile = read(join(src, 'ui/tv/TvSettingsModel.kt'))
const alias = {} // local val -> xml, e.g. "val player = R.xml.player_preferences"
for (const m of modelFile.matchAll(/val (\w+) = R\.xml\.(\w+)/g)) alias[m[1]] = m[2]
let checked = 0
for (const call of modelFile.matchAll(/prefs\(\s*(R\.xml\.\w+|\w+)\s*,([^()]*(?:\([^()]*\)[^()]*)*)\)/g)) {
  const xml = call[1].startsWith('R.xml.') ? call[1].slice(6) : alias[call[1]]
  if (!xml) continue
  const keys = xmlKeys[xml]
  if (!keys) { errors.push(`The preference file ${xml}.xml does not exist any more`); continue }
  for (const token of call[2].match(/"[^"]*"|\b(?:C|TvPrefs)\.\w+/g) ?? []) {
    const key = resolveKey(token)
    checked++
    if (key !== undefined && !keys.has(key)) errors.push(`Preference "${key}" is no longer in ${xml}.xml (used by TvSettingsModel)`)
  }
}

// 2. sub-page XMLs and the navigation actions used to open the classic editors
for (const m of modelFile.matchAll(/R\.xml\.(\w+)/g)) {
  if (!xmlKeys[m[1]]) {
    try { read(join(res, 'xml', `${m[1]}.xml`)) } catch { errors.push(`R.xml.${m[1]} does not exist any more`) }
  }
}
const navIds = new Set()
for (const f of readdirSync(join(res, 'navigation'))) {
  for (const m of read(join(res, 'navigation', f)).matchAll(/android:id="@\+id\/(\w+)"/g)) navIds.add(m[1])
}
for (const m of modelFile.matchAll(/R\.id\.(action_global_\w+)/g)) {
  if (!navIds.has(m[1])) errors.push(`Navigation action ${m[1]} is no longer in the settings navigation graph`)
}

if (errors.length) {
  for (const e of errors) console.log(`::error::${e}`)
  console.log(`TV contract BROKEN: ${errors.length} problem(s) (${checked} settings keys checked)`)
  process.exit(1)
}
console.log(`TV contract OK (${checked} settings keys checked)`)
