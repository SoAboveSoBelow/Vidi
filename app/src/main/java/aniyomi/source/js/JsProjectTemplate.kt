package aniyomi.source.js

/**
 * What a new project starts as.
 *
 * A blank file would be a worse default than this: the contract a project has to
 * satisfy - the five entry points, their argument shapes, and the JSON they
 * return - is not discoverable from an empty editor, and nothing here hosts
 * documentation to send someone to. A skeleton that compiles, runs, and returns
 * one placeholder entry is the shortest route to a person understanding the
 * shape, because they can press run before they have written anything.
 *
 * Indented with tabs, matching the editor's own indent unit, so a project does
 * not end up half tabs and half spaces the first time someone presses the tab
 * button.
 *
 * Deliberately not a working source for any real site. It fetches nothing and
 * names nothing; the commented `http.get` line shows where fetching goes and
 * what it returns, and the person supplies the site.
 */
const val JS_PROJECT_TEMPLATE = """/*
 * A project assigns a global `source` object. Every entry point takes one
 * argument object and returns plain data - no promises, no callbacks.
 *
 * Available globals:
 *   console.log(value)
 *   parse(html, baseUrl)                  -> Node
 *   http.get(url, headers)                -> Response
 *   http.post(url, body, headers)         -> Response
 *
 * Response:  .status  .url  .header(name)  .text()  .html(baseUrl) -> Node
 * Node:      .select(query) -> [Node]      .selectFirst(query) -> Node or null
 *            .attr(name)    .absAttr(name)   .text()   .ownText()   .html()
 *            .textIn(query) .attrIn(query, name) .firstAttr([names])
 *
 * The *In helpers return '' when nothing matches, so a page that is missing a
 * card, a title or a thumbnail does not take the whole page down with it.
 *
 * Field names are checked strictly: a key the host does not know is an error
 * naming the key, rather than a field that silently does nothing.
 *
 *   popular/latest/search -> { entries: [{ url, title, thumbnail }], hasNextPage }
 *   details               -> { url, title, thumbnail, description, author,
 *                              artist, genre: [..], status }
 *   episodes              -> [{ url, name, number, dateUpload, scanlator, summary }]
 *   videos                -> [{ url, quality, headers: {..} }]
 *   filters               -> [{ type, name, values: [..], keys: [..] }]
 *
 * Filter types: select, sort, text, checkbox, tristate, group, header,
 * separator. The app renders them in its own filter sheet and hands the chosen
 * values to search as args.filters, keyed by name - a select reports its
 * matching `keys` entry, a checkbox true/false, a tristate
 * 'ignore'/'include'/'exclude'.
 *
 * status is one of: ongoing, completed, licensed, publishing_finished,
 * cancelled, on_hiatus, upcoming, unknown.
 */

var source = {

	popular: function (args) {
		// var doc = http.get(baseUrl + '/popular?page=' + args.page).html();
		// var items = doc.select('div.card');
		return {
			entries: [
				{ url: '/placeholder', title: 'Placeholder ' + args.page, thumbnail: '' }
			],
			hasNextPage: false
		};
	},

	latest: function (args) {
		return source.popular(args);
	},

	filters: function () {
		return [
			{
				type: 'select',
				name: 'Sort by',
				values: ['Newest', 'Oldest'],
				keys: ['new', 'old']
			}
		];
	},

	search: function (args) {
		var selected = args.filters || {};
		console.log('searching for ' + args.query + ' sorted by ' + selected['Sort by']);
		return source.popular(args);
	},

	details: function (args) {
		return {
			url: args.url,
			title: 'Placeholder',
			description: '',
			author: '',
			genre: ['placeholder'],
			status: 'completed',
			thumbnail: ''
		};
	},

	episodes: function (args) {
		return [
			{ url: args.url, name: 'Episode 1', number: 1 }
		];
	},

	videos: function (args) {
		// http.resolveUrl follows redirects without downloading the file, which
		// is what a CDN link needs; headers are passed to the player, which has
		// no access to this app's cookies.
		return [
			{ url: '', quality: '1080p', headers: { Referer: baseUrl } }
		];
	}
};
"""

/**
 * A directory name derived from what the person typed.
 *
 * The slug is identity - [JsProjectStore.idForSlug] mints the source id from it
 * - so it is derived once, at creation, and never from the editable name again.
 * Renaming a project is then cosmetic, which is the behaviour someone expects,
 * and library entries cannot be orphaned by a typo in a title.
 */
fun slugFor(name: String): String {
    val base = name.lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-{2,}"), "-")
        .take(48)

    return base.ifEmpty { "project" }.let { candidate ->
        // The pattern requires the first character to be alphanumeric.
        if (candidate.first().isLetterOrDigit()) candidate else "p$candidate"
    }
}

/** Appends a counter until the name is free, so creation never fails on a clash. */
fun JsProjectStore.freeSlug(name: String): String {
    val base = slugFor(name)
    if (!exists(base)) return base
    var index = 2
    while (exists("$base-$index")) index++
    return "$base-$index"
}
