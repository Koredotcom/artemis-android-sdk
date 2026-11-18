# Adaptive Cards

The built-in renderer accepts Adaptive Cards through version 1.5 and supports
the same Microsoft Adaptive Cards runtime and Markdown sanitizer as the React
Native SDK. The bundled `artemis/adaptive-runtime.json` contains the runtime,
CSS, Markdown renderer, and upstream license notices; it is not fetched from
the network. Regenerate it from the React Native SDK's
`scripts/generate-adaptive-runtime.cjs` output when upgrading the runtime.

Submit and Execute actions use the existing chat action callback and render ID.
OpenUrl uses the SDK's safe HTTP(S) link handling. ShowCard and
ToggleVisibility are handled locally. Unsupported or invalid cards display a
non-interactive fallback. Remote media still depends on device network policy.
