// KT-68281 reports two sources of non-determinism. Its Kotlin 2.4 fix sorts
// polyfills, but Kotlin 2.4.10 can still emit implemented interfaces in a
// different order between identical compilations. The runtime treats this list
// as a set, but the textual difference propagates through bundlers and gzip.
const metadataInterfaces = /^(\s*initMetadataFor(?:Class|Interface|Object)\([^;\n]*?, \[)([$\w]+(?:,\s*[$\w]+)*)(\]\);\s*)$/gm;

export function normalizeKotlinJsMetadata(source) {
  return source.replace(
    metadataInterfaces,
    (_match, prefix, interfaces, suffix) => {
      const normalized = interfaces
        .split(",")
        .map((name) => name.trim())
        .sort()
        .join(", ");
      return `${prefix}${normalized}${suffix}`;
    },
  );
}
