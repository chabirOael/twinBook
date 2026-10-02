# What the site sends when logged in

Findings from the owner's logged-in recordings of 2026-10-02, for the agents who build M3 to
M10. Every number here can be regenerated with one command (section 0). Nothing in this file
comes from a recorded value: it gives key paths, type names, enum values, counts and sizes.
Key paths are written from the object named at the start of the path; `[]` stands for any
element of an array, `*` for any element in a rule path.

Where to look, by milestone:

| Milestone | Sections |
|---|---|
| M3 (clean web twin) | 9 cookies, 11 mobile site, 12 telemetry |
| M4 (data-layer filtering) | 1 transport, 5 ads and rules v1, 8 route definitions, 10 page documents, 13 numbers |
| M5 (token harvest, replay) | 2 request anatomy, 3 query catalogue, 8 document ids, 9 tokens, 10 page documents |
| M6 (models, normalizer) | 4 story anatomy, 6 video, 7 comments, notifications, profile, fixtures in `fixtures/` |
| M7 to M10 (native screens) | 4, 6, 7, and the gate verdict at the end |

## 0. Recordings and how to regenerate the numbers

| Session | Device | Site | What it holds |
|---|---|---|---|
| `20261002-172602-site` | emulator | mobile, no page load | 140 requests in 155 s: images, media, 1 beacon. No document, no XHR. |
| `20261002-172927-site` | emulator | desktop, no page load | 526 requests in 182 s; 85 own-host XHR with bodies (31 GraphQL). No document. |
| `20261002-181317-site` | emulator | mobile, page load | 287 requests in 84 s; the 138 KB document, a WebSocket, a service-worker script. |
| `20261002-183314-site` | real phone | desktop, page load | 905 requests in 106 s; the 4.04 MB document, 50 GraphQL requests, 7 WebSockets. |

The fourth session is not in the emulator's `daily` app, so it was recorded on the owner's
phone. All four were re-scrubbed with redaction rules v5 before any body was read (section
9 and the appendix). Commands, from the repository root:

```bash
tools/capture-tools.sh rescrub captures/<id>            # -> captures/<id>-rescrub (rules v5)
tools/capture-tools.sh scan captures/<id>-rescrub       # opaque-value scan (appendix)
tools/capture-tools.sh findings captures/<id>-rescrub ...   # every number below
node tools/capture-summary.mjs captures/<id>-rescrub    # per-session overview
```

`findings` takes all four `-rescrub` directories at once; it prints sections numbered like this
document. One recording defect affects every parser (section 13): layer 2 replaced the viewer's
id where the site sends it as a bare JSON number, leaving `!T:cookie:c_user!` unquoted. The
tools turn such a placeholder into `0` before parsing (765 places in the two desktop sessions).

## 1. Transport

GraphQL (`POST https://www.facebook.com/api/graphql/`), 79 responses with a body:

- Content type `text/html; charset="utf-8"` on all 79, never JSON. Encoding `zstd` on all 79,
  over HTTP/3 (status line `HTTP/3.0`); Gecko hands the stream filter decoded bytes.
- No `for (;;);` guard on any GraphQL response.
- A response is newline-delimited JSON: one document per line. 59 responses have one
  document, 20 have several (up to 37). Line sizes 166 B to 1.65 MB, median 47 KB.
- Incremental delivery: the first line is the query's `data`; later lines carry `label`,
  `path` and `data` (Relay `@stream` and `@defer`). Every document has `extensions`; the last
  one has `extensions.is_final: true` (78 of 79 responses; the exception is the truncated
  8.9 MB search response). Other `extensions` keys: `prefetch_uris_v2` (236 documents),
  `all_video_dash_prefetch_representations` (187), `server_metadata` (79, first document only),
  `sr_payload` (78), `fulfilled_payloads` (1).
- Labels seen, by count: `VideoPlayerRelay_video$defer$InstreamVideoAdBreaksPlayer_video`
  (70), `VideoPlayerCometFeedStoryControlsImplNotLive_video$defer$CometAudioLanguageUtils_dubtrackMapping`
  (56), `CometNewsFeed_viewerConnection$stream$CometNewsFeed_viewer_news_feed` (32),
  `FBUnifiedVideoMediaContentContainer_reels$defer$FBUnifiedVideoMediaFooter_footer_eAnYh` (11),
  `FBUnifiedVideoMediaTransitionContainer_video$defer$FBUnifiedVideoFeedbackBar_feedback` (11),
  `CometNewsFeed_viewerConnection$defer$CometNewsFeed_viewer_news_feed$page_info` (8),
  `FBUnifiedVideoContainer_reels$stream$FBUnifiedVideoContainer_video_feed_unit_feed` (8),
  `ProfileCometTimelineFeed_user$stream$ProfileCometTimelineFeed_user_timeline_list_feed_units`
  (5), and ten more with 1 to 3 each (`findings` section 1).
- Timing: 1 to 70 network chunks per response (median 1). First chunk after 12 to 2,348 ms
  (median 79 ms) from the response start. Feed pages: 0.62 to 2.31 MB in 6 to 23 chunks over
  0.5 to 2.8 s, 6 to 30 documents each.

Other own-host XHR (`/ajax/*`): every response with a body is behind a `for (;;);` guard. On
`/ajax/route-definition/` **every line has its own guard** (92 documents behind 92 guards in 4
responses), and one line holds two guarded documents separated by spaces.

Did the stream filter see everything the page consumed? Every own-host XHR or fetch response
with a body was tapped and observed: 79 GraphQL and 146 `/ajax/*` and other responses, bytes
out equal to bytes in on all of them, `failedOpen` 0 everywhere **except
`/ajax/route-definition/`: 88 of its 92 documents failed open** because of the per-line
guards (the core accepts a guard only before the first document). Two GraphQL requests have
no body because the page aborted them (`NS_BINDING_ABORTED`).

Requests without a tab (`tabId -1`), 18 own-host requests in total:

| What | Count | Origin | Stream filter |
|---|---|---|---|
| GraphQL (messaging queries) | 7 | `www.facebook.com/static_resources/webworker/init_script/` | tapped and observed, unchanged |
| `/ajax/bnzai` (logging) | 7 (3 + 4) | same web worker | tapped and observed, unchanged |
| `/ajax/dtsg/` (token refresh) | 1 | same web worker | tapped and observed |
| WebSocket `/ws/lightspeed`, `/ws/chat` | 2 | same web worker | handshake only |
| script `/sw` (service worker) | 3 | the page | not tapped (scripts never are) |

So the three `/ajax/bnzai` posts without a tab in the first desktop session came from the
site's dedicated web worker, not from a service worker, and their responses passed through
the stream filter like any other. No own-host request has the service worker as its origin.
Cache hits (`fromCache` true): 342 on CDN resources (133 scripts, 128 images, 72 media
segments by XHR, 9 others) and 2 on own hosts (an image and a script).

## 2. Request anatomy

A GraphQL request is a form post, `Content-Type: application/x-www-form-urlencoded`, from the
page or the web worker. 81 recorded (31 + 50), 4 field orders. Form fields:

| Field | Behaviour in a session | Notes |
|---|---|---|
| `av`, `__user` | constant | the viewer's id (both show `!T:cookie:c_user!` after layer 2) |
| `__aaid` | constant `0` | |
| `__a` | constant | layer 1 redacts it (was `1` in M2a) |
| `__req` | changes every request | request counter, base 36 |
| `__hs` | constant | haste session; equals `SiteData.haste_session` |
| `dpr` | constant `3` | device pixel ratio |
| `__ccg` | `EXCELLENT` or `GOOD` | connection class |
| `__rev`, `__spin_r`, `__spin_b` (`trunk`), `__spin_t` | constant | site revision; `SiteData.server_revision`, `__spin_*` |
| `__s` | constant per page | session string, 3 colon-separated parts |
| `__hsi` | constant per page | page-load id, `SiteData.hsi` |
| `__dyn`, `__csr`, `__hsdp`, `__hblp`, `__sjsp` | change every few requests | compressed bitmaps of the JavaScript modules loaded so far (85 to 1,237 characters) |
| `__comet_req` | constant `15` | |
| `fb_dtsg` | constant (69 characters) | anti-forgery token, layer 1 redacted |
| `jazoest` | constant (5 characters) | checksum of `fb_dtsg`, layer 1 redacted |
| `lsd` | constant (22 characters) | also sent as header `X-FB-LSD` |
| `__crn` | per route | `comet.fbweb.CometHomeRoute`, `…CometProfileTimelineListViewRoute`, `…CometFBUnifiedVideoTabForYouRoute`, `…CometSearchGlobalSearchDefaultTabRoute` |
| `fb_api_caller_class` | constant `RelayModern` | |
| `fb_api_req_friendly_name` | per query | also sent as header `X-FB-Friendly-Name` |
| `server_timestamps` | constant `true` | |
| `variables` | per request | JSON (section 3) |
| `doc_id` | per query | 16 or 17 digits (section 3) |
| `qpl_active_flow_ids`, `fb_api_analytics_tags` | on 27 of 50 in the phone session | performance-logging ids |

Headers of a GraphQL request from the page, constant in a session: `User-Agent` (GeckoView's
desktop UA), `Accept: */*`, `Accept-Language`, `Accept-Encoding: gzip, deflate, br, zstd`,
`Content-Type`, `X-FB-Friendly-Name`, `X-FB-LSD`, `X-ASBD-ID: 359341`, `Origin:
https://www.facebook.com`, `Referer` (the page URL), `Alt-Used: www.facebook.com`,
`Sec-Fetch-Dest: empty`, `Sec-Fetch-Mode: cors`, `Sec-Fetch-Site: same-origin`, and `Cookie`
with `c_user, datr, dpr, fbl_st, locale, oo, pas, presence, ps_l, ps_n, sb, vpd, wd, wl_cbv,
xs` (the phone adds `m_pixel_ratio`).

Other endpoints carry the same common fields (`__aaid` to `__crn`, without `av`):
`/ajax/bulk-route-definitions/` (`route_urls[n]`, `routing_namespace`),
`/ajax/route-definition/` and `/ajax/navigation/` (`client_previous_actor_id`, `route_url`,
`routing_namespace`, `trace_policy`), `/ajax/relay-ef/` (`queries[n]`), `/video/unified_cvc/`
(`d`). `/ajax/bootloader-endpoint/` is a GET with `__a`, `__user`, `fb_dtsg_ag`, `jazoest` in
the URL. `/ajax/bnzai` posts `ts`, `post_0`, `q` with `fb_dtsg`, `lsd`, `jazoest`, `__user`,
`__a` in the URL.

Compared with replay path B in M1 (anchor page, `content.fetch`; docs/reports/M1.md section 3):
path B sends the site origin as `Origin`, a same-origin `Referer`, the anchor session's desktop
UA, `Sec-Fetch-*` of `empty`/`cors`/`same-origin`, and every cookie. That matches the real
client's network headers. What a replayed request would be missing is in the body and in two
headers the page's code adds:

- headers `X-FB-LSD`, `X-FB-Friendly-Name` and `X-ASBD-ID` (path B must set them; `X-ASBD-ID`
  is a constant of the build);
- the token fields `fb_dtsg`, `jazoest`, `lsd` (section 9 says where to read them);
- the page fields `__hs`, `__rev`, `__spin_*`, `__hsi`, `__s`, `__comet_req`, `__ccg`, `dpr`,
  `__crn` (all readable from a page document, section 10, or constant);
- the module bitmaps `__dyn`, `__csr`, `__hsdp`, `__hblp`, `__sjsp`. They describe the
  JavaScript the page has loaded. A client that loads no site JavaScript cannot compute them.
  Whether the server needs them is open (section 14).
- `__req`, a counter the client can keep itself.

## 3. Query catalogue

43 operations were recorded. The ones a native screen needs:

| Friendly name | Purpose | doc_id | Main variables | Response root | Pagination |
|---|---|---|---|---|---|
| `CometNewsFeedPaginationQuery` | home feed pages 2+ | 39621328754132121 | `count` (5), `cursor`, `feedLocation`, `feedStyle`, `orderby`, `recentVPVs` (viewed posts), `refreshMode`, `renderLocation`, `scale`, 40+ `__relay_internal__pv__*` flags | `data.viewer.news_feed.edges[]` (first edge), then one edge per `$stream$` line, then `page_info` (`$defer$…$page_info`) | `cursor` = previous `end_cursor`, `count` 5 |
| `CometModernHomeFeedQuery` | home feed page 1 | in the document only (section 8) | (preloaded) | same as above, inside the page document | gives the first `end_cursor` |
| `CometSinglePostDialogContentQuery` | one post with its comments | 28632601516392742 | `storyID`, `feedLocation`, `focusCommentID`, `scale` | `data.node_v2` (a `Story`) with comments under `…comment_list_renderer.feedback.comment_rendering_instance_for_feed_location.comments` | comments `page_info` |
| `CommentsListComponentsPaginationQuery` | more comments | 38580553541588665 | (feedback id, cursor) | `data.node` (`Feedback`) `.comment_rendering_instance_for_feed_location.comments.edges[].node` (`Comment`) | `comments.page_info.{end_cursor,has_next_page,start_cursor,has_previous_page}`; replies under `node.feedback.replies_connection.page_info` |
| `CometUFIConversationGuideContainerQuery` | comment composer hints | 25316047617978702 | | `data.feedback.comet_conversation_guide_renderer` | |
| `FBUnifiedVideoRootWithEntrypointQuery` | video tab, first page | 38837439995904355 | `count`, `scale`, `video_feed_context_data{…}`, `should_use_stream`, `stream_initial_count` | `data.viewer.video_feed_unit_feed.edges[].node` | `$defer$…$page_info` → `data.page_info` |
| `FBUnifiedVideoContainerQuery` | video tab, more | 28481227834872342 | `count`, `cursor`, `video_feed_context_data{…}` | `$stream$` edges, `node.attachments[].media` (`Video`) | `data.page_info` |
| `FBUnifiedVideoSeenStateMutation` | marks a video seen | 9749301061815436 | | | |
| `CometNotificationsDropdownQuery` | notifications, first page | 28272355145709625 | | `data.viewer.notifications_page.edges[].node` | `notifications_page.page_info` |
| `CometNotificationsListPaginationQuery` | notifications, more | 28762112060039555 | `count`, `cursor` | same | same |
| `ProfileCometHeaderQuery` | profile header | in route definitions only | | `user.profile_header_renderer.user` | |
| `ProfileCometTimelineFeedQuery` | profile timeline page 1 | in route definitions only | | `user.timeline_list_feed_units.edges[].node` (`Story`) | |
| `ProfileCometTimelineFeedRefetchQuery` | profile timeline, more | 28435847089405766 | `count`, `cursor`, `id`, `scale` | `data.node.timeline_list_feed_units`, then `$stream$` units | `$defer$…$page_info` → `data.page_info` |
| `ProfileCometTilesFeedPaginationQuery` | profile tiles (intro, photos, friends) | 28166951962942499 | `count`, `cursor`, `id` | `data.node.profile_tile_sections.edges[].node` (`ProfileTileSection`) | `profile_tile_sections.page_info` |
| `CometHovercardQueryRendererQuery` | hover card of a person | 28949243598012796 | `entityID`, `context`, `scale` | `data.node.comet_hovercard_renderer.user` | |
| `StoriesTrayRectangularQuery` | stories tray | 28261665493524355 | | `data.node.unified_stories_buckets` | `page_info` |
| `SearchCometResultsPaginatedResultsQuery` | search results, more | 39078816981731517 | | `data.serpResponse.results.edges[]` | `results.page_info` |
| `CometSearchKeywordDataSourceQuery` | search typeahead | 29182563967998876 | | | |
| `CometHomeRightSideEgoRefetchQuery` | right column (contains ads) | 28715630331400170 | `refresh_num`, `scale` | `data.viewer.auxColumnUnits.nodes[]` (`AdsSideFeedUnit`) | |

The rest are chat, settings, composer mention sources and logging: `CometHomeContact*` (4),
`CometSettingsDropdownTriggerQuery`, `CometRightSideHeaderCardsQuery`,
`useFeedComposerCometMentions*` (3), `useInstreamAdsHaloFetcherQuery`,
`CometNotificationsPushTurnOnMutation`, `CometRecordProductUsageMutationMutation`,
`CometAddTypeaheadRecentSearchMutation`, the search bootstrap queries, and 11 messaging
queries (`EBMessageMetadataQueryQuery`, `MessagingWebACS…`, `MAWVerify…`, `useMWEncrypted…`,
`fetchMWChat…`, `RTWebCall…`, `OhaiWebPublicKeyConfigQuery`, `FBYRPTimeLimitsEnforcementQuery`,
`usePseudoBlockedUserInterstitialF3Query`, `CometMessagingJewel…`). Variable names and types of
every operation are in `findings` section 3; the fixtures keep the variables' shape.

The feed request names its own obfuscation. Its variables include
`__relay_internal__pv__GHLShouldChangeSponsoredDataFieldNamerelayprovider` (true),
`…GHLShouldChangeAdIdFieldName…` (true), `…GHLShouldChangeSponsoredAuctionDistanceFieldName…`
(true), `…GHLShouldUseSponsoredAuctionLabelFieldNameV1…` (true), `…V2…` (false),
`shouldObfuscateCategoryField` (true) and three `…BRSLabelFieldName…` (false). These are flags
the page's JavaScript evaluates and sends. The server renames the ad fields accordingly
(section 5). The right column has the same kind of flags (`…GHLShouldChangeRHCAdsFieldName…`).

## 4. Feed story anatomy

44 feed edges: 4 from the phone session's page document (first page), 20 from each desktop
session's `CometNewsFeedPaginationQuery` responses. Every edge has the same ten keys:
`brs_content_label`, `cat_sensitive`, `cursor`, `deduplication_key`, `feed_backend_data`,
`is_ad_eligible_for_ad_pod`, `min_gap_type_index_string`, `node`, `sposnsor_new_distac_action`,
`top_story_cache_score`. 43 nodes are `Story`, 1 is a `ShowcaseFeedUnit` (a row of short
videos).

Where a story keeps its parts (paths from `node`):

| Part | Path | Present |
|---|---|---|
| id | `id` (opaque string), `post_id` (digits), `cache_id`, `feedback.id` | 43/43 |
| actors | `actors[]` with `__typename` (`User` 41, `GroupAnonAuthorProfile` 1), `id`, `name`, `url` | 43/43 |
| author picture | `comet_sections.context_layout.story.comet_sections.actor_photo.story.actors[0].profile_picture.uri` | 43/43 |
| title (who, where) | `comet_sections.context_layout.story.comet_sections.title` (strategy type, see below) | 43/43 |
| time | `comet_sections.timestamp.story.creation_time` (seconds), also `…context_layout.story.comet_sections.metadata[].story.creation_time` and `creation_time` | 38/43; the 5 ads have none |
| audience | `…context_layout.story.comet_sections.metadata[].story.privacy_scope.{description, icon_image.name}` | 43/43 |
| text | `comet_sections.content.story.comet_sections.message.story.message.text` (40), else `comet_sections.content.story.message.text` (1) | 41/43 |
| text ranges | `…message.story.message.ranges[]` = `{offset, length, entity{__typename, id, url, …}, entity_is_weak_reference}`; entity types `Hashtag` 67, `User` 5, `ExternalUrl` 2. Offsets and lengths count UTF-16 units. | 16 stories |
| translation | `…message_container.story.translation.message.{text, ranges[]}` | 4 |
| attachments | `attachments[].styles.__typename` decides the kind; the payload is `attachments[].styles.attachment` (table below); the same attachments appear again under `comet_sections.content.story.attachments[]` | 41/43 |
| reactions | `comet_sections.feedback.story.story_ufi_container.story.feedback_context.feedback_target_with_context.comet_ufi_summary_and_actions_renderer.feedback.adaptive_ufi_action_renderers[].feedback.reaction_count.count`; per type under `…feedback.top_reactions.edges[].{reaction_count, i18n_reaction_count}` | 43/43 (top reactions 36) |
| comments | `…feedback_target_with_context.comment_rendering_instance.comments.total_count` | 43/43 |
| shares | `…adaptive_ufi_action_renderers[].feedback.share_count.count`, `i18n_share_count` | 43/43 |
| group context | title strategy `CometFeedStoryCommunityAttributionTitleStrategy`, metadata `CometStoryCommunityAttributionGroupPostByStrategy`, `Group` and `GroupMemberProfileActionLink` objects | 2 |
| shared story | `attached_story` | 0 (null on all) |
| permalink | `permalink_url` | 43/43 |
| tracking | `encrypted_tracking`, `trackingdata.id`, `click_tracking_linkshim_cb`, `encrypted_click_tracking`, `serialized_frtp_identifiers`, edge `feed_backend_data` | (never needed for display) |

Attachment kinds (`styles.__typename`, count, payload under `styles.attachment`):

| Style | Count | Payload |
|---|---|---|
| `StoryAttachmentPhotoStyleRenderer` | 18 | `media` (`Photo`): `photo_image`, `viewer_image`, `accessibility_caption`, `focus`, `url`, `id` |
| `StoryAttachmentAlbumStyleRenderer` | 6 | `all_subattachments` (photos), `mediaset_token`, `url` |
| `StoryAttachmentUnifiedLightweightVideoStyleRenderer` | 7 | `media` (`Video`, section 6), `style_infos` |
| `StoryAttachmentShareStyleRenderer` | 5 | link: `media` (`GenericAttachmentMedia`: `large_share_image`, `flexible_height_share_image`), `title_with_entities`, `story_attachment_link_renderer` |
| `StoryAttachmentShareAdStyleRender` | 4 | the same as Share, used by ads |
| `StoryAttachmentVideoStyleRenderer` | 1 | `media` (`Video`), `cta_screen_renderer`, `action_links` |
| `StoryAttachmentFallbackStyleRenderer` | 1 | `title`, `description`, `source`, `media`, `url` |

Renderer components (`__module_component_*`, with the edges using them): every story has
`CometFeedUnitContainerSection_feedUnit`, `CometFeedStory{Metadata,Layout,Content,Context,
Title,ActorPhoto}SectionMatchRenderer_story`, `CometFeedStoryFeedbackSection_story`,
`CometFeedStoryCallToActionSection_story`, `CometUFI*` (5); 41 have the message and attachment
match renderers; 38 `CometFeedStoryTimestampSection_story`; 19
`CometFeedStoryFollowButtonSection_story`; 5 `CometFeedStorySponsoredLabelStrategy_sponsoredLabel`
and `CometFeedStoryFooterSection_story`. The full list (38 components, 108 type names) is in
`findings` section 4.

Shapes. Classed by node type, content strategy, title strategy, message strategy, attachment
style and shared story, the 44 edges have **13 distinct shapes**. The largest are photo (18),
lightweight video (6), album (4), ad share (4) and share (3). All 43 stories use
`CometFeedStoryDefaultContentStrategy` and `CometStoryDefaultLayoutStrategy`. Titles are
`CometFeedStoryTitleWithActorStrategy` (41) or the group attribution strategy (2). Messages are
`CometFeedStoryDefaultMessageRenderingStrategy` (39), rich (1) or large (1) message
strategies, or absent (2).

## 5. Ads and suggestions

Classes over the 44 edges: **5 sponsored, 20 suggested, 19 organic**. The owner did not report
a count of sponsored posts seen, so the 5 are what the rules found; they also match by hand:
every one carries every ad signal below and no other edge carries any.

Signals of a sponsored edge (paths from the edge):

| Signal | Path | Sponsored | Suggested | Organic |
|---|---|---|---|---|
| sponsored-data object | `node.th_dat_spo` non-null, type `SponsoredData` (with `client_token`); copies under `node.comet_sections.{content.story, feedback.story.story_ufi_container.story, footer.story, context_layout.story.comet_sections.actor_photo.story, …metadata[].story}.th_dat_spo` carry `ad_id`, `client_token`, `ad_conversion_type`, `ad_sensitive_vertical_info`, `is_demo_ad` | 5/5 | 0/20 | 0/19 |
| edge-level number | `sposnsor_new_distac_action` is a number (null on every other edge) | 5/5 | 0/20 | 0/19 |
| sponsored label | `node.comet_sections.context_layout.story.comet_sections.metadata[].__typename = CometStorySponsoredLabelStrategy`, with `…story.ghl_label.__module_component_CometFeedStorySponsoredLabelStrategy_sponsoredLabel` | 5/5 | 0/20 | 0/19 |
| ad attachment style | `node.attachments[].styles.__typename = StoryAttachmentShareAdStyleRender` | 4/5 | 0/20 | 0/19 |
| no timestamp | no `comet_sections.timestamp` | 5/5 | 0/20 | 0/19 |

The word "Sponsored" is not in the data: the label strategy draws it. No UI string such as
"Suggested for you" occurs in the data either.

Field names are obfuscated on purpose, and the request decides them (section 3):
`th_dat_spo` is the sponsored-data field (the probe's `sponsored_data` key exists only twice,
null, on organic stories), and `sposnsor_new_distac_action` is the sponsored auction distance,
misspelled at the source. The same page could receive `sponsored_data` tomorrow if a flag
flips. Rules must therefore not depend on one field name: each signal of rules v1 also tests
a type name, which belongs to the schema rather than to the query.

Decoys. Ad-shaped keys on the 39 non-sponsored edges, by value: `th_dat_spo` null 183 times,
`sponsored_data` null 2, `whatsapp_ad_context` null 38, `local_alerts_story_menu_promotion` null
38, `is_ad_eligible_for_ad_pod` false 39, `is_additional_profile_plus` (unrelated, a profile
type). **No field on an organic or suggested edge imitates an ad with a value**: every
ad-shaped key there is null or false. The probe's candidate counts in M2a's metadata
(`sponsored_data` 38, `client_token` 17, `ad_id` 11) count keys, most of them null or nested
copies, not ads.

Suggested content (never removed by rules v1, reported for M4's toggles):

| Signal | Path or type | Suggested (20) |
|---|---|---|
| follow button | `…context_layout.story.comet_sections.title.story.comet_sections.follow_button.__typename = CometFeedStoryFollowButtonStrategy` (or `…CollabFollowButtonStrategy`): the viewer does not follow the author | 19 |
| recommendation label | metadata `CometStoryRecommendationLabelStrategy` | 1 |
| preference bumper | `…call_to_action.story.bumpers.persistent_bumper.__typename = IFRBroadTransientPreferenceSignalBumper` | 2 |
| short-video showcase | `node.__typename = ShowcaseFeedUnit` | 1 |

The test account is new, so most of its feed is recommended: the classes say little about a
normal account. The "follow button" signal is an inference (a story by someone the viewer does
not follow); the owner's own count of "Suggested for you" posts would confirm it.

**Rules v1** are data: `rules/ads-v1.json`, run by `extension/src/lib/adRules.ts`, which
returns a `DocumentRule` for the NDJSON stream filter (the M1 rule interface). An edge is an ad
when signals from at least **2 of the 4 families** (`sponsored-data`, `edge-placement`,
`sponsored-label`, `ad-attachment`) match. The rule removes ad edges from
`data.viewer.news_feed.edges`, drops a `$stream$` document whose edge is an ad, drops every
later document whose `path` runs through a removed edge (its video ad breaks, its dubbing
track), and when a dropped document was final, writes `{"extensions":{"is_final":true}}` in
its place.

Result over every recorded feed response, enforce mode:

| Response | Documents | Kept | Dropped | Replaced | Ads removed (families) |
|---|---|---|---|---|---|
| 172927 rid 756 | 6 | 5 | 1 | 0 | 1 (all 4) |
| 172927 rid 773 | 7 | 6 | 1 | 0 | 1 (all 4) |
| 172927 rid 804 | 24 | 13 | 10 | 1 | 1 (3: no ad attachment style; a video ad) |
| 172927 rid 854 | 6 | 6 | 0 | 0 | 0 |
| 183314 rid 1187 | 22 | 21 | 1 | 0 | 1 (all 4) |
| 183314 rid 1248, 1294, 1558 | 30, 14, 7 | all | 0 | 0 | 0 |

4 ads removed from GraphQL responses, 0 organic or suggested edges flagged, 0 parse failures.
The fifth ad is the second edge of the page document's first page (section 10); the NDJSON
filter does not reach it, the document filter would. Every ad matched at least 3 families.

Other ad surfaces seen: the right column (`AdsSideFeedUnit` in `CometHomeRightSideEgoRefetchQuery`
and `CometRightSideHeaderCardsQuery`, 1 each), in-stream video ad breaks
(`instream_video_ad_breaks_comet` with `pre_roll` and `mid_rolls`: non-null on 41 videos,
null on 29, delivered as `VideoPlayerRelay_video$defer$InstreamVideoAdBreaksPlayer_video`
documents), and a Reels ads flag (`…FBReelsMediaFooter_comet_enable_reels_ads_gk…` true).

## 6. Video

229 `Video` objects in GraphQL responses; 74 of them carry a
`videoDeliveryResponseFragment.videoDeliveryResponseResult`, under
`attachments[].styles.attachment.media` in feed stories, `node.attachments[].media` in the
video tab, and the stories tray. Every one of the 74 has:

- `progressive_urls[]`: two entries, `metadata.quality` `HD` and `SD`, each with a
  `progressive_url` (an MP4 on `scontent-*.fbcdn.net`). 74 of 74.
- `dash_manifests[]` with an inline `manifest_xml` (74) and `dash_manifest_urls[]` with a
  `manifest_url` (74).
- `hls_playlist_urls` present but with no URL in any of the 74; `hls_playlist_url` null.
- `captions_url` (32 occurrences with a URL, 73 null) and `video_available_captions_locales`.

Elsewhere on the `Video`: `length_in_second` (76), `playable_duration_in_ms` (67),
`preferred_thumbnail` (74), `first_frame_thumbnail` (65), `width`, `height`, `aspect_ratio`,
`is_live_streaming`, `is_looping`, `loop_count`.

DASH representations with segment ranges travel outside the data tree, in
`extensions.all_video_dash_prefetch_representations` (187 documents): `representations[]`
with `representation_id`, `mime_type`, `codecs`, `bandwidth`, `base_url`, `segments`. By
codec: AV1 `video/mp4 av01` 7,279, VP9 868, H.264 `avc1` 24, audio `mp4a` 1,467.

How the clients fetched media (metadata): the desktop player fetched DASH segments by XHR,
`video/mp4`, status 200, with `bytestart` and `byteend` in the URL and no `Range` header (151
and 146 requests). The mobile site played through a `<video>` element with `Range` requests
(206). All media came from `scontent.fdohN-N.fna.fbcdn.net`. Media URLs are signed (`oh`,
`oe`, `_nc_*` parameters) and expire.

For an Android player: the progressive HD/SD MP4 is enough for a first version (Media3
`ProgressiveMediaSource`). Adaptive playback can use the inline `manifest_xml` with Media3's
DASH source; most video representations are AV1, which needs a hardware decoder or the dav1d
extension on older phones, and VP9 and H.264 exist for fewer videos. Expired URLs must be
re-fetched through the query (M9's recovery).

## 7. Comments, notifications, profile

Comments (`CometSinglePostDialogContentQuery`, `CommentsListComponentsPaginationQuery`): 110
`Comment` objects, 55 complete. A comment node has `author`, `body`, `preferred_body`,
`body_renderer`, `created_time`, `depth`, `feedback` (reactions, `replies_connection`),
`attachments`, `comment_action_links`, `comment_direct_parent`, `is_author_weak_reference`,
`legacy_fbid`, `translatability_for_viewer` and 20 more (`findings` section 7). The list is
`…comment_rendering_instance_for_feed_location.comments.edges[].node`, paged by
`comments.page_info.{end_cursor, has_next_page}` (and `start_cursor`, `has_previous_page`);
replies page through `node.feedback.replies_connection.page_info`. Recorded: the dialog's first
comments (2 posts) and 4 more pages. Missing: a replies page, and the comment-ordering
variants.

Notifications (`CometNotificationsDropdownQuery`, `…ListPaginationQuery`): rows under
`data.viewer.notifications_page.edges[].node` with types `NotifPageNotificationRow`,
`NotifPageBucketHeaderRow`, `NotifPageSeePreviousButtonRow`. A notification row has `notif`
with `body` (text with ranges), `creation_time`, `url`, `navigation_endpoint`, `icon_data`,
`notif_image`, `notif_type`, `seen_state`, `notif_id`, `notif_attachments`, `tracking`.
Recorded: the dropdown twice and 3 more pages, which is enough for a list screen. Missing: the
mark-as-seen mutation and the badge count query (preloaded in the document only).

Profile: the header (`ProfileCometHeaderQuery`: `user.profile_header_renderer.user` with
`name`, `alternate_name`, `cover_photo`, `profile_picture_for_sticky_bar`,
`profile_social_context`, `is_viewer_friend`, header action bars) and the first timeline page
(`ProfileCometTimelineFeedQuery`) arrived **inside `/ajax/route-definition/` responses**, as
documents `{__type: "preloader", id, result: {complete, result, sequence_number}}`, not from
`/api/graphql/`. Later timeline pages use `ProfileCometTimelineFeedRefetchQuery` (stories of the
feed's shape) and tiles `ProfileCometTilesFeedPaginationQuery`. Missing: the header query on its
own over `/api/graphql/` (its `queryID` is known from the route definition, section 8), about,
photos and friends tabs, and pages (as opposed to people).

## 8. Navigation endpoints and document ids

| Endpoint | Responses | What it returns | Native client needs it? |
|---|---|---|---|
| `/ajax/bulk-route-definitions/` | 65 | `payload.payloads`: a map from route URL to `{error, result}`; `result.exports` holds `rootView`, `hostableView`, `canonicalRouteName`, `tracePolicy`, `entityKeyConfig`, `prefetchable` …, for 568 routes | no (route metadata for the JavaScript app) |
| `/ajax/route-definition/` | 4 | the route's definition, then the route's **preloaded query results** as `{__type: "preloader", …}` documents (profile header and timeline, search results) | yes, for profile and search data, unless M5 calls those queries directly |
| `/ajax/navigation/` | 3 | one route definition | no |
| `/ajax/bootloader-endpoint/` | 35 | `hrp` (resource map for JavaScript and CSS) | no |
| `/ajax/relay-ef/` | 3 | `predictions`, `consistency` | no |
| `/ajax/dtsg/` | 1 (web worker) | `{token, valid_for: 86400, expire}` | yes, for token refresh (section 9) |

Where document ids come from. Of the 43 `doc_id`s used by recorded GraphQL requests, **only 7
appear in any recorded non-GraphQL response**. Document ids are delivered as `queryID` in
preloader entries `{actorID, preloaderID, queryID, queryName, variables}`:

- the desktop page document lists 27 preloaders: `CometModernHomeFeedQuery`,
  `CometNotificationsBadgeCountQuery`, `StoriesTrayRectangularRootQuery`, `CometHomeContentQuery`,
  `CometRightSideHeaderCardsQuery`, the 4 `CometHomeContact*`, 3 Lightspeed messaging queries,
  settings, search bootstrap and recent, and 10 more;
- `/ajax/route-definition/` lists the route's preloaders: `ProfileCometHeaderQuery`,
  `ProfileCometTimelineFeedQuery`, `ProfileCometTimelineListViewRootQuery`,
  `ProfileCometTopAppSectionQuery`, `SearchCometResultsInitialResultsQuery` and its parallel
  fetch.

The ids of the queries a client calls later, among them `CometNewsFeedPaginationQuery`,
`CommentsListComponentsPaginationQuery`, `CometNotificationsListPaginationQuery`,
`FBUnifiedVideo*`, `ProfileCometTimelineFeedRefetchQuery` and `CometSinglePostDialogContentQuery`,
are in no recorded response. They must be in the JavaScript bundles
(`static.xx.fbcdn.net/rsrc.php/…`, 124 script requests in the desktop sessions), whose bodies
the recorder does not keep. M5 therefore cannot get them from a plain document alone. Options
are in section 14.

## 9. Tokens and cookies

Where each token appears (values redacted; lengths after redaction equal the originals):

| Token | Where |
|---|---|
| `fb_dtsg` (69 characters) | form field of every GraphQL and `/ajax/*` post; URL of `/ajax/bnzai`; document modules `DTSGInitialData.token`, `DTSGInitData.token`, `MRequestConfig.dtsg.{token, valid_for, expire}`; refreshed by `/ajax/dtsg/` |
| `fb_dtsg_ag` | URL of `/ajax/bootloader-endpoint/`; document `DTSGInitData.async_get_token`, `MRequestConfig.dtsg_ag.{token, valid_for, expire}` |
| `lsd` (22) | form field and header `X-FB-LSD`; document module `LSD.token` |
| `jazoest` (5) | form field and URLs, next to `fb_dtsg` |
| viewer id | cookie `c_user`, fields `__user` and `av`, document `CurrentUserInitialData.USER_ID` |
| site revision | `SiteData.{server_revision, client_revision, haste_session, hsi, __spin_r, __spin_b, __spin_t}` |
| `dtsgToken`, `dtsgAsyncGetToken` | top-level keys of route responses; null in all 85 |
| mobile site | `fb_dtsg` and `jazoest` in the URL of `/ajax/weblite_load_logging/` beacons; a `dtsg` key in the document |

Rotation: `/ajax/dtsg/` reports `valid_for` 86,400 s and an expiry 86,399 s after the request,
so `fb_dtsg` lives 24 hours. Within each session every token field had one length and layer 2
recorded one label per token. The recording keeps no values, so it cannot show whether a
value changed. In the phone session the page's requests carried 2 values each of `__s`,
`__hsi` and `__ccg` (a second page context with no recorded document), and the web worker
its own `__s` and `__hsi`.

Cookies sent to own hosts: `c_user, datr, dpr, fbl_st, locale, oo, pas, presence, ps_l, ps_n,
sb, vpd, wd, wl_cbv, xs`, plus `m_pixel_ratio` on the phone. Set during the recordings: `ps_l`
(400 days, Secure, HttpOnly, SameSite=Lax), `ps_n` (400 days, SameSite=None), `pas` (400 days,
SameSite=Lax), all on `.facebook.com`. The login cookies (`c_user`, `xs`, `datr`, `sb`, `fr`)
were set before any capture, so their server lifetimes are not recorded. The document's
`CookieCoreConfig` gives the lifetimes of cookies the page's code writes: `c_user` 1 year
(SameSite=None), `fbl_st` 1 year (Strict), `presence` 30 days, `wl_cbv` 90 days, `vpd` 60
days, `dpr`, `locale`, `wd`, `m_pixel_ratio` 7 days. For M3: a login survives an app restart
only if `xs` and `c_user` have an expiry, which needs the login response itself (section 14).

## 10. Page documents

Desktop (`www.facebook.com/`, phone session): 4.04 MB, 39 chunks over 3.1 s. 363 script
elements: 197 external, 164 JSON islands `<script type="application/json" data-sjs>` (3.56
MB together, largest 968 KB), 2 other JSON islands. All 164 islands parse (after the
placeholder repair of section 0) and no other script element carries data.

- Data sits in `RelayPrefetchedStreamCache` "next" entries inside `ScheduledServerJS`
  islands: 40 prefetched results. The first feed page is `CometModernHomeFeedQuery`: one first
  result (edge 0), 3 `$stream$` edges, 6 deferred video ad-break documents and the
  `page_info`. The first feed preloader starts at byte 478,391 of 4,037,772 (12 %).
- Tokens and ids sit in `ServerJS` `define` entries (1,112 modules): `DTSGInitialData`,
  `DTSGInitData`, `LSD`, `CurrentUserInitialData`, `SiteData`, `MRequestConfig`,
  `MessengerWebInitData`, `RelayAPIConfigDefaults` (with an empty `accessToken`),
  `CookieCoreConfig`, `ServerNonce`.
- Preloaders with `queryID`: 27 (section 8).

So a plain fetch of the document, with no script run, gives M5: `fb_dtsg`, `fb_dtsg_ag`, `lsd`,
the viewer id, the revision fields (`__rev`, `__spin_*`, `__hs`, `__hsi`), the first feed page
with its `end_cursor`, and 27 preloader document ids, but not the pagination query's id
(section 8).

Mobile (`m.facebook.com/`): 138 KB in 5 chunks; 30 inline scripts, no JSON island, no
external script element (8 scripts are loaded later from `z-m-static.xx.fbcdn.net`). See
section 11.

## 11. The mobile site

What the recordings show. The logged-in mobile site is a "web lite" client:

- While the owner scrolled the feed, opened comments, watched videos and opened a profile
  (session 172602, 155 s), the site's own hosts received **one** beacon
  (`/ajax/weblite_load_logging/`) and one image request. No XHR, no fetch, no document.
- With a page load (session 181317): the document (138 KB, 30 inline scripts, no JSON
  islands); a **WebSocket** to `wss://kaios-d.facebook.com/ws/<number>` with URL parameters
  `lid` and `cm`, `Origin: https://m.facebook.com`, `permessage-deflate`, answered `101` over
  HTTP/1.1, opened from the page (tab 10003); the script `/sw` fetched twice without a tab (a
  service worker); 15 beacons to `/ajax/weblite_load_logging/` and
  `/ajax/weblite_resources_timing_logging/`; a web manifest; images and media from fbcdn.
- The document mentions `weblite` 10 times, `bloks` 21, `Bloks` 8, `WebSocket` 4, `kaios` 2,
  `serviceWorker` 2, and never `graphql`, `__bbox` or `RelayPrefetchedStreamCache`.

So every feed story, comment and video reference reaches the mobile page as WebSocket frames.
WebExtensions have no API to read or change WebSocket frames, and hooking the page's
`WebSocket` would mean running code in the page's realm, which the design forbids (Ghost Owl,
PLAN section 3). What can be done on the logged-in mobile site:

- filtered or blocked at the network layer: the document (once per load), beacons, the service
  worker script, media and image requests by URL;
- not filtered at the data layer: anything inside the socket, which includes the feed and its
  ads.

Removing ads in the mobile web fallback therefore needs cosmetic filtering (CSS hiding by
uBlock Origin rules, or styles injected by the app), which depends on the page's markup and
needs maintained selectors. See the report's section 5 for what this means for the plan.

## 12. Telemetry and third parties

Own-host logging endpoints (count, request body bytes from `Content-Length`):

| Endpoint | Count | Bytes | Notes |
|---|---|---|---|
| `/ajax/bnzai` | 16 | 37,850 | Banzai logging; 7 from the web worker |
| `/ajax/comet_error_reports/` | 13 | 6,448 | client error reports |
| `/video/unified_cvc/` | 5 | 15,972 | video view counting |
| `/ajax/weblite_load_logging/` | 13 beacons | | mobile site |
| `/ajax/weblite_resources_timing_logging/` | 3 beacons | | mobile site |
| `/ajax/qm/` | 1 beacon | 171 | desktop |
| GraphQL `FBUnifiedVideoSeenStateMutation` | 2 | | seen state |
| GraphQL `CometRecordProductUsageMutationMutation` | 1 | | product usage |

Tracking also rides inside data requests: every feed page request sends `recentVPVs` (the
posts viewed since the last page, with `qid`, `vsid`, `vspos`, `timestamp`,
`feed_backend_data_serialized_payloads`), and stories carry `encrypted_tracking` and
click-tracking fields.

Hosts outside the site's own: only `fbcdn.net` (images, scripts, media, CSS, fonts) and one
`www.fbsbx.com` sub-frame on the desktop page. No Google or other third-party host in any
logged-in session (the logged-out mobile page of M2a loaded Google pixels through fbsbx.com).

## 13. Numbers

263 GraphQL documents scanned (the truncated search response excluded): 51,020 number literals,
335 of them not integers. **No integer above 2^53.** Ids are strings. 8 literals are written in
exponent form (shape `9.9e-9`), which JavaScript re-serializes in another form with the same
value. `JSON.stringify(JSON.parse(line))` differs from the line for 225 documents, because the
site escapes `/` as `\/` (184 documents) and non-ASCII as `\uXXXX` (212), plus whitespace in
3. **Re-serializing a document is safe for values** (no precision loss) but never
byte-identical, so the enforce filter should keep re-serializing only the documents it
changes, as it does.

Recording defect found here: layer 2 replaces a secret wherever its bytes occur. The viewer id
also occurs as a bare JSON number (`"userID":<digits>` and in arrays), where the placeholder
`!T:cookie:c_user!` becomes invalid JSON: 364 places in session 172927 and 401 in 183314.
The analysis repairs them. The live traffic was never affected. A fix belongs in the
finalize pass: a numeric placeholder for digit-only values in number positions.

## 14. Open questions

| Question | What would answer it |
|---|---|
| Where do the document ids of later queries (feed pagination, comments, notifications, video, profile refetch) come from without running the site's JavaScript? | Record the bodies of `rsrc.php` script responses (a recorder option, own risk: large) or fetch the bundles named by `/ajax/bootloader-endpoint/` in M5 and look for `__d("<Name>_facebookRelayOperation", …)`. A HAR from desktop Firefox with response bodies would also show it. |
| Does `/api/graphql/` accept a request without `__dyn`, `__csr`, `__hsdp`, `__hblp`, `__sjsp`? | One replayed request in M5 (live). |
| Does the server honour `__relay_internal__pv__GHLShouldChange…FieldName = false` and return `sponsored_data`? | One replayed feed request in M5 with the flags flipped. |
| Does dropping an `edges[n]` stream document leave a hole that Relay mis-renders, and do deferred documents for a dropped edge break anything? | M4 on-device test with the enforce filter on the real site (or a mock that reproduces `@stream` paths with Relay). |
| Lifetimes of `xs`, `c_user`, `datr`, `fr` as set at login. | A capture that includes the login response, with the password typed outside the capture (the checklist forbids typing credentials during a capture). |
| Is "follow button" a reliable marker of "Suggested for you"? | The owner counts the "Suggested for you" posts while recording; compare with the rule's count. |
| Ads in profile timelines, search results and the video tab: same signals? | Recordings that scroll further on those surfaces; the fixtures have none. |
| Does `/ajax/route-definition/` need filtering for ads (search results carried video ad breaks)? | M4: handle per-line guards in the NDJSON core, then run rules over its preloader documents. |
| Can the logged-in mobile site be switched to an HTTP data client (for example by another user agent or `mbasic`)? | A logged-out probe in M3 of the alternatives; out of scope here. |
| Do mobile site socket frames carry Bloks payloads with the same ad markers? | Only by decoding the socket, which is out of scope. |

## Gate verdict (M2b 5.4)

Criteria and numbers, over the 43 stories of the 44 recorded feed edges:

| Field | Located | Path holds across stories of a shape |
|---|---|---|
| author name | 43/43 | 12 of 12 shapes |
| author picture | 43/43 | 12 of 12 |
| time | 38/43; the 5 misses are the 5 ads, which have no time by design | 10 of 12 (the two ad shapes) |
| text | 41/43; the 2 misses have no message section (optional) | 11 of 12 |
| primary attachment | 41/43; the 2 misses are text-only (optional) | 10 of 12 |
| reaction count | 43/43 | 12 of 12 |
| comment count | 43/43 | 12 of 12 |

- Ads: 4 independent signal families exist; every recorded ad matched 3 or 4, no other edge
  matched any.
- Video: a playable source is present for 74 of 74 delivery results (progressive HD and SD
  MP4, plus a DASH manifest).
- Pagination: understood. `count` 5, `cursor` = previous `end_cursor` = the last edge's
  `cursor`, in 7 of 7 consecutive pairs; the first `end_cursor` comes from the document.

Verdict: **pass with reservations.** The GraphQL data supports native feed, video,
notifications and profile screens. The reservations: document ids of follow-up queries are not
obtainable from a plain document (section 8); profile and search data arrive through
`/ajax/route-definition/`, which the stream filter cannot parse today (section 1); the mobile
web fallback cannot be filtered at the data layer at all (section 11); and the recordings are
one new account on one day. The biggest risk is M5's harvest: without running the desktop
application, the ids of the pagination queries must come from the script bundles.

## Appendix: opaque-value scan and redaction decisions (M2b 5.1)

`tools/capture-tools.sh scan` lists, per session, every JSON key, form field, URL parameter and
header whose values are at least 16 characters, opaque (token characters, no spaces, not a URL
or placeholder) and recur in two or more records. It also lists every credential-like key name
with an opaque value whether it recurs or not, and every long unredacted string below a
credential-like parent key. It prints names, lengths and counts only.

Redaction rules grew from version 1 to 5 (`extension/data/redaction-rules.json`, 76 keys):

| Version | Keys added | Why |
|---|---|---|
| 2 | `accessToken`, `refreshToken`, `idToken`, `authToken`, `oauthToken`, `sessionToken`, `xsrfToken`, `csrf`, `xsrf`, `dtsg`, `fbDtsg`, `dtsg_ag`, `asyncGetToken`, `userAccessToken`, `user_access_token`, `pageAccessToken`, `page_access_token`, `appsecret_proof`, `signed_request`, `signedRequest`, `client_secret`, `clientSecret`, `passwd`, `pwd`, `loginNonce`, `login_nonce`, `machineId` | camel-case and other spellings; exact-name matching misses them. `csrfToken`, `sessionKey` and `contactPoint` were already covered (case-insensitive). The re-scrub found one value: a `dtsg` key in the mobile document. |
| 3 | `logoutToken`, `logout_hash`, `secret`, `accountKey`, `accountKeyV2`, `userKeyBase`, `ServerNonce`, `compat_iframe_token`, `link_react_default_hash`, `untrusted_link_default_hash`, `device_id`, `deviceId`, `e2eeDeviceID`, `openDeviceID`, `x-dgw-deviceid` | credential-like names with opaque values in the phone session's document; 28 values, 3 more occurrences of the gateway device id scrubbed by layer 2 |
| 4 | `encrypted_serialized_cat`, `encrypted`, `dtsgToken`, `dtsgAsyncGetToken`, `cryptoAuthToken` | a messaging crypto auth token and a response-verification token whose own key names are generic (`MessengerWebInitData.cryptoAuthToken`, `MRequestConfig.ajaxResponseToken`) |
| 5 | `data` | anonymous credentials issued to the messaging web worker (`MessagingWebACSGraphQLServerProviderTokenIssuanceQuery`, `credentials[].evaluation/proof[].data`); only string values are replaced, 37 in all sessions |

Keys the scan lists that are **not** credentials, with the decision:

| Keys | Decision |
|---|---|
| `__hs`, `__hsi`, `__s`, `brsid`, `webSessionId`, `session_id`, `sessionID`, `client_viewer_session_id`, `profile_session_id`, `serp_session_id`, `typeahead_session_id`, `haste_session` | session-shaped: identify a page load or a logging session; grant nothing. Replaced in fixtures. |
| `__dyn`, `__csr`, `__hsdp`, `__hblp`, `__sjsp` | client state (module bitmaps). Neither. |
| `_nc_*`, `oh`, `oe`, `efg`, `stp`, `ccb`, `_nc_map`, `tag` | CDN URL signatures and parameters: they let anyone fetch that media until it expires, but grant no account access. Never in fixtures. |
| `x-fb-ptm-uuid`, `x-fb-debug`, `content-digest`, `content-md5`, `etag`, `sec-websocket-key`, `cross-origin-opener-policy` | response or protocol headers. Neither. |
| `cursor`, `end_cursor`, `story_token`, `legacy_token`, `expansion_token`, `intent_token`, `page_token`, `mediaset_token`, `sectionToken`, `rawSectionToken`, `collectionToken`, `notif_filter_token`, `uri_token`, `reference_token`, `client_vpv_token`, `default_key`, `deduplication_key`, `tab_key` | content and pagination tokens of the schema; they reference content, not the session. Replaced in fixtures. |
| `client_token`, `encrypted_tracking`, `encrypted_click_tracking`, `trackingCode`, `tracePolicy`, `serialized_frtp_identifiers`, `encrypted_server_defined_experience`, `__cft__`, `qid`, `original_qid`, `vsid` | ad and click tracking. Neither, but identifying; replaced in fixtures. |
| `id`, `post_id`, `video_id`, `feedback_id`, `storyID`, `thread_key`, `fbid`, `notif_id`, `comment_id`, `share_fbid`, `encrypted_backup.id` | content and person identifiers: personal data, not credentials. Remapped in fixtures. |
| `public_key`, `appServerKey`, `x-dgw-appid`, `app_id` | public keys and application ids. Neither. |
