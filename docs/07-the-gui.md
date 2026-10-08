## 7. The GUI

The GUI is no longer part of Core. It is its own optional module,
[`gui/`](../gui/README.md) (`dev.px.gui`), built on Core's `layout` and `render`
packages; see its [README](../gui/README.md) and [an example click GUI](../gui/EXAMPLE.md).
Its widgets are headless: the client registers a renderer per widget type that
describes it with Core's `Content`, marking where a container's children go with
`Content.slot()`.

Core keeps everything a GUI is built on — the `layout` box model, the `Render`
facade, fonts, themes and animation — and depends on no GUI at all. A client
that writes its own interface uses those directly and never pulls in the module.
