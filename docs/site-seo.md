# Search and discovery for the website

How the site at <https://pintofi.github.io/beanbook/> is made findable, what is left for the
owner to do, and why some usual advice does not apply. The page rules are enforced by
`tools/site-seo.py check`, which CI runs on every pull request.

## Where the site lives, and what follows

The site is a GitHub Pages *project* site, so its host is `pintofi.github.io` and the site is one
folder on it.

- **There is no `robots.txt`.** Google reads it only at the root of a host
  (`pintofi.github.io/robots.txt`) and says it "cannot be placed in a subdirectory". A file in
  `site/` would be ignored. That address returns 404 today, which means crawlers may fetch
  everything, which is what we want. Creating a repository named `pintofi.github.io` would give
  the host a root, and a `robots.txt` with a `Sitemap:` line, but it would also make that repository
  the site at the host root. That is a decision for the owner, and nothing below needs it.
- **There is a sitemap.** Google's rule is that a sitemap covers the URLs under its own
  directory, so `site/sitemap.xml` is right for a site that lives under `/beanbook/`. The pages
  workflow writes it when the site is published, with each page's `lastmod` taken from git.
  It is not kept in the repository. Google ignores `lastmod` from sites whose dates are not
  reliable, and ignores `priority` and `changefreq` entirely, so those are left out.
- **Site name markup does not apply.** Google's `WebSite` structured data only works for the root of
  a host. A project site cannot set it.

## What each page now carries

- A title and a meta description that are unique to the page, and say what is on it. The home
  page's title is `Beanbook: Beancount and Fava on Android`, which names the things people search for.
- An absolute `rel="canonical"` link that points at the page itself, matching `og:url`.
- One `<h1>`, and an `alt` on every image (already true; now checked).
- On the home page, `MobileApplication` structured data: name, description, category, Android 10
  or later, free, download link, licence, screenshots. **It has no rating**, because the app has
  none and one must not be invented. Google needs a rating or a review for the rich result, so this
  markup describes the app to search engines but will not produce a rich result until there is a
  real rating to state.
- A `404.html` that links to Home, Download and Help, marked `noindex`. `keys.html`, which only
  redirects to the support page, is also `noindex` and is left out of the sitemap.

## What the owner has to do

Nothing here can be done from the repository, because each step needs an account.

1. **Google Search Console.** Add a *URL-prefix* property for `https://pintofi.github.io/beanbook/`.
   Verify it with the HTML-tag method (give the token to a maintainer, who adds one `<meta>` line to
   `site/index.html`). Then submit `https://pintofi.github.io/beanbook/sitemap.xml` under Sitemaps,
   and use URL Inspection on the home page to request indexing. Indexing takes days to weeks.
2. **Bing Webmaster Tools.** Import the site from Search Console, so Bing gets the same sitemap.
3. **Check after a few weeks.** `site:pintofi.github.io/beanbook` in a search, and the Pages report
   in Search Console.

## Getting links and readers

Google says new pages are found mainly through links from pages it already knows, and that
word of mouth is among the most lasting ways to promote a site. Over-promoting looks like
manipulation, so these are places the people who use Beancount already look, not a campaign.

- **awesome-beancount** (github.com/siddhantgoel/awesome-beancount) has a *User Interfaces* section
  that already lists Fava and Beancount Mobile. A pull request adding Beanbook fits there.
- **plaintextaccounting.org** has a "UI, mobile" list of apps. Updates are welcome through its
  GitHub repository, Matrix or IRC. Its community pages also point to the forum
  (forum.plaintextaccounting.org, which has a News category), `r/plaintextaccounting`, and the
  Beancount mailing list.
- **F-Droid and Google Play listings** each have a website field. When they exist, point them at
  the site, since a store page is a link people trust. The F-Droid recipe in this repository has
  no `WebSite:` line yet; it belongs in the fdroiddata metadata when that is next edited.
- **GitHub.** The repository's website field and topics are already set. A social preview image
  can be set under Settings, General, if it is not already.
- **A page that answers the question people type**, such as "Beancount on Android", is worth writing
  once there is something to say that the home page does not.

None of these has been done: each one speaks for the project in a place the project does not
control, so each is the owner's call.

## Deliberately not done

- No `keywords` meta tag: Google does not use it.
- No `FAQPage` markup: Google's page says the FAQ result stopped appearing in Search in 2026.
- No IndexNow: it needs a key file and a call from CI after each publish, and Bing Webmaster Tools can import the site from Search Console without either.
- No rating markup, for the reason above.

## Sources

Google Search Central: [robots.txt](https://developers.google.com/search/docs/crawling-indexing/robots/create-robots-txt),
[sitemaps](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap),
[titles](https://developers.google.com/search/docs/appearance/title-link),
[descriptions](https://developers.google.com/search/docs/appearance/snippet),
[canonical URLs](https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls),
[software app markup](https://developers.google.com/search/docs/appearance/structured-data/software-app),
[site names](https://developers.google.com/search/docs/appearance/site-names),
[SEO starter guide](https://developers.google.com/search/docs/fundamentals/seo-starter-guide).
[awesome-beancount](https://github.com/siddhantgoel/awesome-beancount) and
[plaintextaccounting.org](https://plaintextaccounting.org/) for where Beancount tools are listed.
