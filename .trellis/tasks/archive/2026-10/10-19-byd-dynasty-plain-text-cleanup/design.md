# Technical Design - BYD Dynasty Miniapp Draft Plain Text Cleanup

## Architecture and Boundaries

### Ownership

- `BydDynastyPushService` owns BYD payload compilation and keeps the existing authorization, job creation, image reference and audit flow.
- A new package-private pure component, `BydDynastyPlainTextRenderer`, owns all Markdown-to-plain-text policy.
- The Windows Local Agent remains unchanged and continues to translate only `text` and `img` payload messages into MMKV messages.
- `ArticleVersionEntity.contentMd` and `title` remain the source of truth and are never mutated.

### Data Flow

```text
ArticleVersion.contentMd
  -> BydDynastyPlainTextRenderer
     -> commonmark Parser + extensions
     -> custom TextContentRenderer overrides
     -> ordered text/image blocks
  -> BydDynastyPushService assigns BODY-n image refs
  -> existing payload v1
  -> existing Windows Local Agent
  -> existing MMKV draft writer
```

The renderer does not know about MMKV, HTTP, database entities, BYD upload URLs, or Agent state.

## Dependencies

Add these Maven dependencies at version `0.30.1`:

```text
org.commonmark:commonmark
org.commonmark:commonmark-ext-gfm-tables
org.commonmark:commonmark-ext-gfm-strikethrough
org.commonmark:commonmark-ext-task-list-items
org.commonmark:commonmark-ext-autolink
```

The project already owns Jsoup for HTML handling. No new HTML sanitizer or Markdown parser is introduced.

## Renderer Contract

`BydDynastyPlainTextRenderer` exposes two pure operations to the service:

```java
static List<Block> compile(String markdown)
static String renderInline(String markdown)
```

`Block` is an internal sealed result with two variants:

```java
TextBlock(String text)
ImageBlock(String url)
```

`compile` preserves order between text and image blocks. `renderInline` removes formatting and collapses output to one line for the separate payload title.

### Parsing

The parser uses:

- CommonMark core parser
- GFM tables extension
- GFM strikethrough extension
- Task-list-items extension
- Autolink extension so bare URLs are recognized and can be removed by policy

The renderer uses commonmark's `TextContentRenderer` with `LineBreakRendering.SEPARATE_BLOCKS` as the base. Commonmark owns Markdown syntax, AST traversal, emphasis, paragraphs, lists and code blocks.

### Custom Node Policy

Only product-specific output policies are implemented as small node renderers:

| Node | Output policy |
| --- | --- |
| `Link` | Render child text only; omit destination and title. If an autolink's visible text equals its destination, omit the URL text. |
| `Image` | Store destination as an `ImageBlock`; write a private sentinel into the text stream so image order is preserved. |
| `Strikethrough` | Render children only, no `/.../` decoration. |
| `BlockQuote` | Render children only, no `«»` decoration. |
| `Code` | Render literal only, no added quotation marks. |
| `ThematicBreak` | Emit a block boundary only, no `***`. |
| `HtmlInline` / `HtmlBlock` | Convert through Jsoup to text; `<br>` and block tags become line breaks; remove dangerous/embedded nodes. |
| GFM table nodes | Emit one readable line per row using `column: value; column: value`. |
| Task-list marker | Emit `☑` or `☐` followed by a space. |

The renderer uses a NUL-delimited private sentinel for image boundaries. CommonMark replaces literal NUL input, so ordinary article content cannot collide with the sentinel.

## Text Normalization

After node rendering:

- normalize CRLF and CR to LF
- decode HTML entities
- trim line-end whitespace
- collapse three or more newlines to at most two
- trim leading and trailing blank lines
- drop empty text blocks
- preserve emoji code points, including variation selectors and zero-width joiner sequences
- do not remove code content or ordinary punctuation

Title rendering additionally collapses all whitespace to spaces, removes image/link destinations, and trims the result.

## Service Integration

`BydDynastyPushService` will:

1. Replace the `MD_IMAGE` regex, `parseMessages` and `normalizeText` methods with calls to `BydDynastyPlainTextRenderer.compile`.
2. Convert each `TextBlock` into `{"type":"text","content":"..."}`.
3. Convert each `ImageBlock` into `{"type":"img","imageRef":"BODY-n"}` while preserving order.
4. Use `renderInline` for `payload.title`.
5. Preserve the current cover insertion, image source list, job payload version, draft id and Agent result flow.
6. Preserve the current Chinese empty-content error when the compiled result contains no text and no image.

The `SafeBody` container can remain as a service-local payload builder, but it must receive the renderer's ordered output rather than performing its own parsing.

## Compatibility and Rollout

- Payload schema remains version 1; no Local Agent deployment is required.
- No DB migration is required.
- Existing dirty payloads already stored in `PENDING` jobs are not rewritten. Before deployment, finish or cancel those jobs or recreate them after rollout.
- Rollback is a code revert to the previous service compilation behavior; no data rewrite is needed.
- The new dependency versions are pinned in `pom.xml` to keep repeatable builds.

## Error and Security Handling

- No parser failure is allowed to produce a partially cleaned payload silently.
- Empty compiled content fails before the job is inserted.
- Jsoup parses strings only; it must not fetch remote URLs.
- Raw HTML images are not converted into BYD images in this task; they are removed with their markup to avoid untrusted source URLs entering the media path.
- Link destinations and bare URLs are excluded from visible text to reduce review and accidental external-link exposure.
- No script, style, iframe, object or embed content is emitted.
- Existing secret/path isolation behavior in payload construction remains unchanged.

## Test Strategy

### Renderer Unit Tests

Cover:

- headings, paragraphs and blank-line normalization
- bold, italic, strikethrough and inline code
- fenced/indented code
- explicit links and bare URLs
- unordered, ordered and nested lists
- block quotes
- GFM tables
- task-list markers
- `<br>`, block HTML, inline HTML and entities
- script/style/iframe removal
- emoji and Unicode punctuation preservation
- image order and text blocks around images
- empty input and image-only input

### Service Contract Tests

Extend `BydDynastyPushServiceTest` to assert:

- no Markdown or HTML remains in text messages
- links do not leak destinations
- `BODY-n` image order remains stable
- cover image remains first
- payload version remains 1
- original `ArticleVersionEntity` is unchanged
- empty cleaned content does not insert a job

### Validation Commands

```powershell
mvn -Dtest=BydDynastyPlainTextRendererTest,BydDynastyPushServiceTest test
mvn test
git diff --check
```

No test may write a real MMKV file or create a real BYD draft.