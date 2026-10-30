var COUB_URL = 'https://coub.com';
var API = COUB_URL + '/api/v2';
var SITE_API = COUB_URL + '/api/coub-site/timeline';
var timelineCursors = {};
var loggedIn = null;
var REQUEST_HEADERS = { 'Accept': 'application/json', 'Referer': COUB_URL + '/' };

function requestHeaders() {
	var headers = {
		'Accept': 'application/json',
		'Referer': COUB_URL + '/',
		'X-Requested-With': 'XMLHttpRequest'
	};
	var cookies = http.cookiesFor(COUB_URL);
	if (cookies) headers.Cookie = cookies;
	return headers;
}

function getJson(url) {
	var response = http.get(url, requestHeaders());
	if (response.status() === 401 || response.status() === 403) {
		throw new Error('Coub login required or expired. Open Coub in WebView, log in, then retry.');
	}
	if (response.status() < 200 || response.status() >= 300) {
		throw new Error('Coub returned HTTP ' + response.status() + ' for ' + url);
	}
	return JSON.parse(response.text());
}

function permalink(args) {
	var match = /\/view\/([^\/?#]+)/.exec(args.url || '');
	if (match === null) throw new Error('Expected a Coub /view/ URL, got: ' + args.url);
	return match[1];
}

function tags(coub) {
	var values = [];
	var lists = [coub.tags || [], coub.communities || [], coub.categories || []];
	for (var i = 0; i < lists.length; i++) {
		for (var j = 0; j < lists[i].length; j++) {
			var item = lists[i][j];
			var value = item.label || item.title;
			if (value && values.indexOf(value) < 0) values.push(value);
		}
	}
	return values;
}

function media(coub) {
	var files = coub.file_versions || {};
	var html5 = files.html5 || {};
	var video = html5.video || {};
	var audio = html5.audio || {};
	var dimensions = coub.size || (coub.dimensions || {}).big || [];
	return {
		video: (video.high || video.med || {}).url || (files.mobile || {}).video || '',
		audio: (audio.high || audio.med || {}).url || coub.audio_file_url || '',
		share: (files.share || {}).default || '',
		width: dimensions[0] || 0,
		height: dimensions[1] || 0
	};
}

function hasSession() {
	if (loggedIn !== null) return loggedIn;
	var response = http.get(COUB_URL + '/', requestHeaders());
	loggedIn = response.status() >= 200 && response.status() < 300 &&
		/isLoggedIn(?:\\"|\"):true/.test(response.text());
	return loggedIn;
}

function addActions(feed, liked, bookmarked) {
	if (!hasSession()) return;
	feed.actions = [
		{ id: 'like', icon: 'favorite', label: 'Like on Coub', active: !!liked },
		{ id: 'bookmark', icon: 'bookmark', label: 'Bookmark on Coub', active: !!bookmarked }
	];
}

function entry(coub) {
	var clip = media(coub);
	var feed = { video: clip.video, width: clip.width, height: clip.height, loop: true };
	if (clip.audio) feed.audio = clip.audio;
	addActions(feed, coub.like, coub.favourite);
	return {
		url: COUB_URL + '/view/' + coub.permalink,
		title: coub.title || 'Untitled Coub',
		thumbnail: coub.picture || coub.timeline_picture || '',
		description: (coub.views_count || 0) + ' views · ' + (coub.likes_count || 0) + ' likes',
		author: (coub.channel || {}).title || '',
		genre: tags(coub),
		status: 'completed',
		memo: { feed: feed }
	};
}

function listing(url, page) {
	loggedIn = null;
	var data = getJson(url);
	var coubs = data.coubs || [];
	var entries = [];
	for (var i = 0; i < coubs.length; i++) {
		if (coubs[i].permalink && media(coubs[i]).video) entries.push(entry(coubs[i]));
	}
	return { entries: entries, hasNextPage: page < (data.total_pages || 0) };
}

function siteEntry(coub) {
	var counters = coub.counters || {};
	var feed = {
		video: coub.videoHdUrl || coub.videoSdUrl || coub.videoLowUrl,
		width: coub.width || coub.posterWidth || 0,
		height: coub.height || coub.posterHeight || 0,
		loop: true
	};
	if (coub.audioUrl || coub.audioMedUrl) feed.audio = coub.audioUrl || coub.audioMedUrl;
	addActions(feed, coub.like || coub.liked, coub.favourite || coub.favourited || coub.bookmarked);
	return {
		url: COUB_URL + '/view/' + coub.permalink,
		title: coub.title || 'Untitled Coub',
		thumbnail: coub.posterUrl || coub.firstFrameUrl || '',
		description: (counters.views || 0) + ' views · ' + (counters.likes || 0) + ' likes',
		author: (coub.channel || {}).title || '',
		genre: (coub.tags || []).concat(coub.community ? [coub.community.title] : []),
		status: 'completed',
		memo: { feed: feed }
	};
}

function siteListing(kind, scope, community, page) {
	var key = kind + '|' + (scope || '') + '|' + (community || '');
	var cursor = page === 1 ? '' : timelineCursors[key + '|' + page];
	if (cursor === undefined || cursor === null) {
		return { entries: [], hasNextPage: false };
	}
	var url = SITE_API + '?kind=' + encodeURIComponent(kind) + '&cursor=' + encodeURIComponent(cursor);
	if (scope) url += '&scope=' + encodeURIComponent(scope);
	if (community) url += '&community=' + encodeURIComponent(community);
	loggedIn = null;
	var data = getJson(url);
	var entries = [];
	var items = data.items || [];
	for (var i = 0; i < items.length; i++) {
		if (items[i].permalink && (items[i].videoSdUrl || items[i].videoHdUrl)) {
			entries.push(siteEntry(items[i]));
		}
	}
	if (data.next) timelineCursors[key + '|' + (page + 1)] = data.next;
	return { entries: entries, hasNextPage: !!data.next };
}

var source = {
	meta: {
		baseUrl: COUB_URL,
		defaultBrowseDisplayMode: 'scroller'
	},

	popular: function (args) {
		return listing(API + '/timeline/hot?page=' + args.page, args.page);
	},

	latest: function (args) {
		return listing(API + '/timeline/fresh?page=' + args.page, args.page);
	},

	filters: function () {
		return [
			{ type: 'select', name: 'Feed', values: ['Hot', 'Fresh', 'My likes', 'Bookmarks'], keys: ['hot', 'fresh', 'likes', 'bookmarks'] },
			{
				type: 'select', name: 'Community',
				values: ['All', 'Animals & Pets', 'Blogging', 'Stand-up & Jokes', 'Mashup', 'Movies & TV', 'Gaming', 'Cartoons', 'Art & Design', 'Live Pictures', 'Music', 'Sports', 'Science & Technology', 'Food & Kitchen', 'Celebrity', 'Nature & Travel', 'Fashion & Beauty', 'Dance', 'Auto & Technique', 'Memes', 'Anime'],
				keys: ['', 'animals-pets', 'blogging', 'standup-jokes', 'mashup', 'movies', 'gaming', 'cartoons', 'art', 'live-pictures', 'music', 'sports', 'science-technology', 'food-kitchen', 'celebrity', 'nature-travel', 'fashion', 'dance', 'cars', 'memes', 'anime']
			}
		];
	},

	search: function (args) {
		if (!args.query) {
			var feed = args.filters ? args.filters.Feed : 'hot';
			var community = args.filters ? args.filters.Community : '';
			if (feed === 'likes' || feed === 'bookmarks') {
				return siteListing('hot', feed === 'likes' ? 'likes' : 'bookmarks', community, args.page);
			}
			if (community) return siteListing(feed === 'fresh' ? 'fresh' : 'hot', '', community, args.page);
			if (feed === 'fresh') return source.latest(args);
			return source.popular(args);
		}
		return listing(API + '/search/coubs?q=' + encodeURIComponent(args.query) + '&page=' + args.page, args.page);
	},

	details: function (args) {
		var details = entry(getJson(API + '/coubs/' + permalink(args)));
		details.url = args.url;
		return details;
	},

	episodes: function (args) {
		var coub = getJson(API + '/coubs/' + permalink(args));
		return [{
			url: args.url,
			name: coub.title || 'Coub',
			number: 1,
			dateUpload: Date.parse(coub.published_at || coub.created_at) || 0,
			scanlator: (coub.channel || {}).title || '',
			summary: (coub.views_count || 0) + ' views · ' + (coub.likes_count || 0) + ' likes'
		}];
	},

	feedAction: function (args) {
		var coub = getJson(API + '/coubs/' + permalink(args));
		var endpoint = args.action === 'like' ? 'likes' :
			args.action === 'bookmark' ? 'favourites' : '';
		if (!endpoint) throw new Error('Unknown Coub action: ' + args.action);
		var url = API + '/' + endpoint + '?id=' + encodeURIComponent(coub.id) +
			'&channel_id=' + encodeURIComponent((coub.channel || {}).id || '');
		var headers = requestHeaders();
		var active = args.action === 'like' ? !!coub.like : !!coub.favourite;
		var response = active ? http.delete(url, headers) : http.post(url, '', headers);
		if (response.status() === 401 || response.status() === 403) {
			throw new Error('Coub login required or expired. Open Coub in WebView and log in again.');
		}
		if (response.status() < 200 || response.status() >= 300) {
			throw new Error('Coub returned HTTP ' + response.status());
		}
		return !active;
	},

	videos: function (args) {
		var clip = media(getJson(API + '/coubs/' + permalink(args)));
		var url = clip.share || clip.video;
		if (!url) throw new Error('Coub returned no playable video');
		return [{ url: url, quality: 'Coub', headers: REQUEST_HEADERS }];
	}
};
