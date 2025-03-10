/* -*- Mode: indent-tabs-mode: nil; js-indent-level: 2 -*- */
/* vim: set sts=2 sw=2 et tw=80: */
"use strict";

const { newURI } = Services.io;

const server = createHttpServer({ hosts: ["example.com"] });
server.registerDirectory("/data/", do_get_file("data"));

async function test_url_matching({
  manifestVersion = 2,
  allowedOrigins = [],
  checkPermissions,
  expectMatches,
}) {
  let policy = new WebExtensionPolicy({
    id: "foo@bar.baz",
    mozExtensionHostname: "88fb51cd-159f-4859-83db-7065485bc9b2",
    baseURL: "file:///foo",

    manifestVersion,
    allowedOrigins: new MatchPatternSet(allowedOrigins),
    localizeCallback() {},
  });

  let contentScript = new WebExtensionContentScript(policy, {
    checkPermissions,

    matches: new MatchPatternSet(["http://*.foo.com/bar", "*://bar.com/baz/*"]),

    excludeMatches: new MatchPatternSet(["*://bar.com/baz/quux"]),

    includeGlobs: ["*flerg*", "*.com/bar", "*/quux"].map(
      glob => new MatchGlob(glob)
    ),

    excludeGlobs: ["*glorg*"].map(glob => new MatchGlob(glob)),
  });

  equal(
    expectMatches,
    contentScript.matchesURI(newURI("http://www.foo.com/bar")),
    `Simple matches include should ${expectMatches ? "" : "not "} match.`
  );

  equal(
    expectMatches,
    contentScript.matchesURI(newURI("https://bar.com/baz/xflergx")),
    `Simple matches include should ${expectMatches ? "" : "not "} match.`
  );

  ok(
    !contentScript.matchesURI(newURI("https://bar.com/baz/xx")),
    "Failed includeGlobs match pattern should not match"
  );

  ok(
    !contentScript.matchesURI(newURI("https://bar.com/baz/quux")),
    "Excluded match pattern should not match"
  );

  ok(
    !contentScript.matchesURI(newURI("https://bar.com/baz/xflergxglorgx")),
    "Excluded match glob should not match"
  );
}

add_task(function test_WebExtensionContentScript_urls_mv2() {
  return test_url_matching({ manifestVersion: 2, expectMatches: true });
});

add_task(function test_WebExtensionContentScript_urls_mv2_checkPermissions() {
  return test_url_matching({
    manifestVersion: 2,
    checkPermissions: true,
    expectMatches: false,
  });
});

add_task(function test_WebExtensionContentScript_urls_mv2_with_permissions() {
  return test_url_matching({
    manifestVersion: 2,
    checkPermissions: true,
    allowedOrigins: ["<all_urls>"],
    expectMatches: true,
  });
});

add_task(function test_WebExtensionContentScript_urls_mv3() {
  // checkPermissions ignored here because it's forced for MV3.
  return test_url_matching({
    manifestVersion: 3,
    checkPermissions: false,
    expectMatches: false,
  });
});

add_task(function test_WebExtensionContentScript_mv3_all_urls() {
  return test_url_matching({
    manifestVersion: 3,
    allowedOrigins: ["<all_urls>"],
    expectMatches: true,
  });
});

add_task(function test_WebExtensionContentScript_mv3_wildcards() {
  return test_url_matching({
    manifestVersion: 3,
    allowedOrigins: ["*://*.foo.com/*", "*://*.bar.com/*"],
    expectMatches: true,
  });
});

add_task(function test_WebExtensionContentScript_mv3_specific() {
  return test_url_matching({
    manifestVersion: 3,
    allowedOrigins: ["http://www.foo.com/*", "https://bar.com/*"],
    expectMatches: true,
  });
});

add_task(async function test_WebExtensionContentScript_isUserScript() {
  let policy = new WebExtensionPolicy({
    id: "foo@bar.baz",
    mozExtensionHostname: "ba159687-9472-4816-a1b2-8b14721d2ea6",
    baseURL: "file:///foo/",
    manifestVersion: 3,
    allowedOrigins: new MatchPatternSet(["https://example.com/*"]),
    localizeCallback() {},
  });

  // WebExtensionContentScript defaults to world "ISOLATED", but for user
  // scripts only "MAIN" and "USER_SCRIPT" worlds are permitted. "MAIN" is
  // supported by user scripts and non-userScripts, so use that here.
  const world = "MAIN";

  const matches = new MatchPatternSet(["https://example.com/match/*"]);
  const includeGlobs = [new MatchGlob("*/glob/*")];
  const exampleMatchesURI = newURI("https://example.com/match/");
  const exampleGlobURI = newURI("https://example.com/glob/");
  const exampleNotMatchedURI = newURI("https://example.com/nomatch/");
  const exampleNoPermissionURI = newURI("https://example.net/glob/");

  let defaultScript = new WebExtensionContentScript(policy, {
    world,
    matches,
    includeGlobs,
  });
  let nonUserScript = new WebExtensionContentScript(policy, {
    isUserScript: false,
    world,
    matches,
    includeGlobs,
  });
  let userScript = new WebExtensionContentScript(policy, {
    isUserScript: true,
    world,
    matches,
    includeGlobs,
  });

  // Sanity checks: isUserScript flag is accurate.
  equal(defaultScript.isUserScript, false, "isUserScript defaults to false");
  equal(nonUserScript.isUserScript, false, "isUserScript set to false");
  equal(userScript.isUserScript, true, "isUserScript set to true");

  // Default, equivalent to isUserScript=false: matches AND includeGlobs.
  ok(
    !defaultScript.matchesURI(exampleMatchesURI),
    "By default: ignore matches if includeGlobs does not match"
  );
  ok(
    !defaultScript.matchesURI(exampleGlobURI),
    "By default: ignore includeGlobs if matches does not match"
  );

  // With isUserScript=false explicitly: matches AND includeGlobs
  ok(
    !nonUserScript.matchesURI(exampleMatchesURI),
    "Non-userScript: ignore matches if includeGlobs does not match"
  );
  ok(
    !nonUserScript.matchesURI(exampleGlobURI),
    "non-userScript: ignore includeGlobs if includeGlobs does not match"
  );

  // With isUserScript=true explicitly: matches OR includeGlobs
  ok(
    userScript.matchesURI(exampleMatchesURI),
    "userScript: accept matches even if includeGlobs does not match"
  );
  ok(
    userScript.matchesURI(exampleGlobURI),
    "userScript: accept includeGlobs even if matches does not match"
  );
  ok(
    !userScript.matchesURI(exampleNoPermissionURI),
    "userScript: ignore includeGlobs if permission is missing"
  );

  // Now verify that empty matches is permitted.
  let nonUserScriptEmptyMatches = new WebExtensionContentScript(policy, {
    isUserScript: false,
    world,
    matches: [],
    includeGlobs,
  });
  let userScriptEmptyMatches = new WebExtensionContentScript(policy, {
    isUserScript: true,
    world,
    matches: [],
    includeGlobs,
  });
  ok(
    !nonUserScriptEmptyMatches.matchesURI(exampleGlobURI),
    "non-userScript: ignore includeGlobs with empty matches"
  );
  ok(
    userScriptEmptyMatches.matchesURI(exampleGlobURI),
    "userScript: accept includeGlobs despite empty matches"
  );
  ok(
    !userScriptEmptyMatches.matchesURI(exampleNotMatchedURI),
    "userScript: ignore when not matched by includeGlobs (and empty matches)"
  );
  ok(
    !userScriptEmptyMatches.matchesURI(exampleNoPermissionURI),
    "userScript: ignore includeGlobs (and empty matches) without permission"
  );

  // Now verify that without includeGlobs, that matches works as usual.
  // The isUserScript=false case is already extensively covered elsewhere, so
  // we just do a sanity check for isUserScript=true.
  let userScriptNoGlobs = new WebExtensionContentScript(policy, {
    isUserScript: true,
    world,
    matches,
  });
  ok(
    userScriptNoGlobs.matchesURI(exampleMatchesURI),
    "userScript: accept matches when includeGlobs is null"
  );
  ok(
    !userScriptNoGlobs.matchesURI(exampleNotMatchedURI),
    "userScript: ignore when not matched by matches and includeGlobs is null"
  );
});

add_task(function test_WebExtensionContentScript_restricted() {
  let tests = [
    {
      manifestVersion: 2,
      permissions: [],
      expect: false,
    },
    {
      manifestVersion: 2,
      permissions: ["mozillaAddons"],
      expect: true,
    },
    {
      manifestVersion: 3,
      permissions: [],
      expect: false,
    },
    {
      manifestVersion: 3,
      permissions: ["mozillaAddons"],
      expect: true,
    },
  ];

  for (let { manifestVersion, permissions, expect } of tests) {
    let policy = new WebExtensionPolicy({
      id: "foo@bar.baz",
      mozExtensionHostname: "88fb51cd-159f-4859-83db-7065485bc9b2",
      baseURL: "file:///foo",

      manifestVersion,
      permissions,
      allowedOrigins: new MatchPatternSet(["<all_urls>"]),
      localizeCallback() {},
    });
    let contentScript = new WebExtensionContentScript(policy, {
      checkPermissions: true,
      matches: new MatchPatternSet(["<all_urls>"]),
    });

    // AMO is on the extensions.webextensions.restrictedDomains list.
    equal(
      expect,
      contentScript.matchesURI(newURI("https://addons.mozilla.org/foo")),
      `Expect extension with [${permissions}] to ${expect ? "" : "not"} match`
    );
  }
});

async function test_frame_matching(meta) {
  if (AppConstants.platform == "linux") {
    // The windowless browser currently does not load correctly on Linux on
    // infra.
    return;
  }

  let baseURL = `http://example.com/data`;
  let urls = {
    topLevel: `${baseURL}/file_toplevel.html`,
    iframe: `${baseURL}/file_iframe.html`,
    dataURL: "data:,data-URL",
    javascriptVoid: "javascript://void",
    javascriptTrue: "javascript:true",
    srcdoc: "about:srcdoc",
    aboutBlank: "about:blank",
  };

  let contentPage = await ExtensionTestUtils.loadContentPage(urls.topLevel);

  let tests = [
    {
      matches: ["http://example.com/data/*"],
      contentScript: {},
      topLevel: true,
      iframe: false,
      dataURL: false,
      javascriptVoid: false,
      javascriptTrue: false,
      aboutBlank: false,
      srcdoc: false,
    },

    {
      matches: ["http://example.com/data/*"],
      contentScript: {
        frameID: 0,
      },
      topLevel: true,
      iframe: false,
      dataURL: false,
      javascriptVoid: false,
      javascriptTrue: false,
      aboutBlank: false,
      srcdoc: false,
    },

    {
      matches: ["http://example.com/data/*"],
      contentScript: {
        allFrames: true,
      },
      topLevel: true,
      iframe: true,
      dataURL: false,
      javascriptVoid: false,
      javascriptTrue: false,
      aboutBlank: false,
      srcdoc: false,
    },

    {
      matches: ["http://example.com/data/*"],
      contentScript: {
        allFrames: true,
        matchAboutBlank: true,
      },
      topLevel: true,
      iframe: true,
      // data-URLs used to inherit the principal until Firefox 57 (bug 1324406)
      // but never did so in Chrome. In any case, match_about_blank should not
      // match documents at data:-URLs.
      dataURL: false,
      javascriptVoid: true,
      javascriptTrue: true,
      aboutBlank: true,
      srcdoc: true,
    },

    {
      // Note: Chrome triggers a hard error when matchOriginAsFallback is used
      // without a wildcard path (/*). We fail gracefully for simplicity.
      matches: ["http://example.com/data/*"],
      contentScript: {
        allFrames: true,
        matchOriginAsFallback: true,
      },
      topLevel: true,
      iframe: true,
      // matchOriginAsFallback only matches 'matches' if the match pattern's
      // path component is a wildcard. Since 'matches' is not, nothing happens.
      dataURL: false,
      javascriptVoid: true,
      javascriptTrue: true,
      aboutBlank: true,
      srcdoc: true,
    },

    {
      matches: ["http://example.com/*"],
      contentScript: {
        allFrames: true,
        matchOriginAsFallback: true,
      },
      topLevel: true,
      iframe: true,
      dataURL: true,
      javascriptVoid: true,
      javascriptTrue: true,
      aboutBlank: true,
      srcdoc: true,
    },

    {
      // pattern in "matches" does not match.
      matches: ["http://foo.com/data/*"],
      contentScript: {
        allFrames: true,
        matchAboutBlank: true,
      },
      topLevel: false,
      iframe: false,
      dataURL: false,
      javascriptVoid: false,
      javascriptTrue: false,
      aboutBlank: false,
      srcdoc: false,
    },
  ];

  // matchesWindowGlobal tests against content frames
  await contentPage.spawn([{ tests, urls, meta }], args => {
    let { manifestVersion = 2, allowedOrigins = [], expectMatches } = args.meta;

    this.windows = new Map();
    this.windows.set(this.content.location.href, this.content);
    for (let c of Array.from(this.content.frames)) {
      let url = c.location.href;
      if (url === "about:blank") {
        // When javascript: URLs are loaded, the resulting URL is about:blank.
        // Look up the frame's "src" attribute to distinguish them.
        url = c.frameElement.src;
      }
      this.windows.set(url, c);
    }
    const { MatchPatternSet, WebExtensionContentScript, WebExtensionPolicy } =
      Cu.getGlobalForObject(Services);
    this.policy = new WebExtensionPolicy({
      id: "foo@bar.baz",
      mozExtensionHostname: "88fb51cd-159f-4859-83db-7065485bc9b2",
      baseURL: "file:///foo",

      manifestVersion,
      allowedOrigins: new MatchPatternSet(allowedOrigins),
      localizeCallback() {},
    });

    let tests = args.tests.map(t => {
      t.contentScript.matches = new MatchPatternSet(t.matches);
      t.script = new WebExtensionContentScript(this.policy, t.contentScript);
      return t;
    });
    for (let [i, test] of tests.entries()) {
      for (let [frame, url] of Object.entries(args.urls)) {
        let should = test[frame] ? "should" : "should not";
        let wgc = this.windows.get(url).windowGlobalChild;
        Assert.equal(
          test.script.matchesWindowGlobal(wgc),
          test[frame] && expectMatches,
          `Script ${i} ${should} match the ${frame} frame`
        );
      }
    }
  });

  await contentPage.close();
}

add_task(function test_WebExtensionContentScript_frames_mv2() {
  return test_frame_matching({
    manifestVersion: 2,
    expectMatches: true,
  });
});

add_task(function test_WebExtensionContentScript_frames_mv3() {
  return test_frame_matching({
    manifestVersion: 3,
    expectMatches: false,
  });
});

add_task(function test_WebExtensionContentScript_frames_mv3_all_urls() {
  return test_frame_matching({
    manifestVersion: 3,
    allowedOrigins: ["<all_urls>"],
    expectMatches: true,
  });
});

add_task(function test_WebExtensionContentScript_frames_mv3_wildcards() {
  return test_frame_matching({
    manifestVersion: 3,
    allowedOrigins: ["*://*.example.com/*"],
    expectMatches: true,
  });
});

add_task(function test_WebExtensionContentScript_frames_mv3_specific() {
  return test_frame_matching({
    manifestVersion: 3,
    allowedOrigins: ["http://example.com/*"],
    expectMatches: true,
  });
});
