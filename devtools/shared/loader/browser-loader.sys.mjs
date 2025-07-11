/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import * as BaseLoader from "resource://devtools/shared/loader/base-loader.sys.mjs";
import {
  require as devtoolsRequire,
  loader,
} from "resource://devtools/shared/loader/Loader.sys.mjs";

const flags = devtoolsRequire("devtools/shared/flags");
const { joinURI } = devtoolsRequire("devtools/shared/path");
const { assert } = devtoolsRequire("devtools/shared/DevToolsUtils");

const lazy = {};

loader.lazyRequireGetter(
  lazy,
  "getMockedModule",
  "resource://devtools/shared/loader/browser-loader-mocks.js",
  {}
);

const BROWSER_BASED_DIRS = [
  "resource://devtools/client/inspector/boxmodel",
  "resource://devtools/client/inspector/changes",
  "resource://devtools/client/inspector/computed",
  "resource://devtools/client/inspector/events",
  "resource://devtools/client/inspector/flexbox",
  "resource://devtools/client/inspector/fonts",
  "resource://devtools/client/inspector/grids",
  "resource://devtools/client/inspector/layout",
  "resource://devtools/client/inspector/markup",
  "resource://devtools/client/jsonview",
  "resource://devtools/client/netmonitor/src/utils",
  "resource://devtools/client/shared/fluent-l10n",
  "resource://devtools/client/shared/redux",
  "resource://devtools/client/shared/vendor",
  // Ensure loading debugger modules in the document scope
  // when they are loaded from SmartTrace, which requires to load Reps/ObjectInspector
  // in a document scope
  "resource://devtools/client/debugger/src",
];

const COMMON_LIBRARY_DIRS = ["resource://devtools/client/shared/vendor"];

const VENDOR_URI = "resource://devtools/client/shared/vendor/";
const REACT_ESM_MODULES = new Set([
  VENDOR_URI + "react-dev.js",
  VENDOR_URI + "react.js",
  VENDOR_URI + "react-dom-dev.js",
  VENDOR_URI + "react-dom.js",
  VENDOR_URI + "react-dom-factories.js",
  VENDOR_URI + "react-dom-server-dev.js",
  VENDOR_URI + "react-dom-server.js",
  VENDOR_URI + "react-prop-types-dev.js",
  VENDOR_URI + "react-prop-types.js",
  VENDOR_URI + "react-test-renderer.js",
]);

// Any directory that matches the following regular expression
// is also considered as browser based module directory.
// ('resource://devtools/client/.*/components/')
//
// An example:
// * `resource://devtools/client/inspector/components`
// * `resource://devtools/client/inspector/shared/components`
const browserBasedDirsRegExp =
  /^resource\:\/\/devtools\/client\/\S*\/components\//;

/*
 * Create a loader to be used in a browser environment. This evaluates
 * modules in their own environment, but sets window (the normal
 * global object) as the sandbox prototype, so when a variable is not
 * defined it checks `window` before throwing an error. This makes all
 * browser APIs available to modules by default, like a normal browser
 * environment, but modules are still evaluated in their own scope.
 *
 * Another very important feature of this loader is that it *only*
 * deals with modules loaded from under `baseURI`. Anything loaded
 * outside of that path will still be loaded from the devtools loader,
 * so all system modules are still shared and cached across instances.
 * An exception to this is anything under
 * `devtools/client/shared/{vendor/components}`, which is where shared libraries
 * and React components live that should be evaluated in a browser environment.
 *
 * @param string baseURI
 *        Base path to load modules from. If null or undefined, only
 *        the shared vendor/components modules are loaded with the browser
 *        loader.
 * @param Object window
 *        The window instance to evaluate modules within
 * @param Boolean useOnlyShared
 *        If true, ignores `baseURI` and only loads the shared
 *        BROWSER_BASED_DIRS via BrowserLoader.
 * @return Object
 *         An object with two properties:
 *         - loader: the Loader instance
 *         - require: a function to require modules with
 */
export function BrowserLoader(options) {
  const browserLoaderBuilder = new BrowserLoaderBuilder(options);
  return {
    loader: browserLoaderBuilder.loader,
    require: browserLoaderBuilder.require,
    lazyRequireGetter: browserLoaderBuilder.lazyRequireGetter,
  };
}

/**
 * Private class used to build the Loader instance and require method returned
 * by BrowserLoader(baseURI, window).
 *
 * @param string baseURI
 *        Base path to load modules from.
 * @param Function commonLibRequire
 *        Require function that should be used to load common libraries, like React.
 *        Allows for sharing common modules between tools, instead of loading a new
 *        instance into each tool. For example, pass "toolbox.browserRequire" here.
 * @param Boolean useOnlyShared
 *        If true, ignores `baseURI` and only loads the shared
 *        BROWSER_BASED_DIRS via BrowserLoader.
 * @param Object window
 *        The window instance to evaluate modules within
 */
function BrowserLoaderBuilder({
  baseURI,
  commonLibRequire,
  useOnlyShared,
  window,
}) {
  assert(
    !!baseURI !== !!useOnlyShared,
    "Cannot use both `baseURI` and `useOnlyShared`."
  );

  const loaderOptions = devtoolsRequire("@loader/options");

  const opts = {
    sandboxPrototype: window,
    sandboxName: "DevTools (UI loader)",
    paths: loaderOptions.paths,
    // Make sure `define` function exists.  This allows defining some modules
    // in AMD format while retaining CommonJS compatibility through this hook.
    // JSON Viewer needs modules in AMD format, as it currently uses RequireJS
    // from a content document and can't access our usual loaders.  So, any
    // modules shared with the JSON Viewer should include a define wrapper:
    //
    //   // Make this available to both AMD and CJS environments
    //   define(function(require, exports, module) {
    //     ... code ...
    //   });
    //
    // Bug 1248830 will work out a better plan here for our content module
    // loading needs, especially as we head towards devtools.html.
    supportAMDModules: true,
    requireHook: (id, require) => {
      // If |id| requires special handling, simply defer to devtools
      // immediately.
      if (loader.isLoaderPluginId(id)) {
        return devtoolsRequire(id);
      }

      let uri = require.resolve(id);

      // The mocks can be set from tests using browser-loader-mocks.js setMockedModule().
      // If there is an entry for a given uri in the `mocks` object, return it instead of
      // requiring the module.
      if (flags.testing && lazy.getMockedModule(uri)) {
        return lazy.getMockedModule(uri);
      }

      // Load all React modules as ES Modules, in the Browser Loader global.
      // For this we have to ensure using ChromeUtils.importESModule with `global:"current"`,
      // but executed from the Loader global scope. `syncImport` does that.
      if (REACT_ESM_MODULES.has(uri)) {
        uri = uri.replace(/.js$/, ".mjs");
        const moduleExports = syncImport(uri);
        return moduleExports.default || moduleExports;
      }
      if (uri.endsWith(".mjs")) {
        const moduleExports = syncImport(uri);
        return moduleExports.default || moduleExports;
      }

      if (
        commonLibRequire &&
        COMMON_LIBRARY_DIRS.some(dir => uri.startsWith(dir))
      ) {
        return commonLibRequire(uri);
      }

      // Check if the URI matches one of hardcoded paths or a regexp.
      const isBrowserDir =
        BROWSER_BASED_DIRS.some(dir => uri.startsWith(dir)) ||
        uri.match(browserBasedDirsRegExp) != null;

      if ((useOnlyShared || !uri.startsWith(baseURI)) && !isBrowserDir) {
        return devtoolsRequire(uri);
      }

      return require(uri);
    },
    globals: {
      // Allow modules to use the window's console to ensure logs appear in a
      // tab toolbox, if one exists, instead of just the browser console.
      console: window.console,
      // Allow modules to use the DevToolsLoader lazy loading helpers.
      loader: {
        lazyGetter: loader.lazyGetter,
        lazyServiceGetter: loader.lazyServiceGetter,
        lazyRequireGetter: this.lazyRequireGetter.bind(this),
      },
    },
  };

  const mainModule = BaseLoader.Module(baseURI, joinURI(baseURI, "main.js"));
  this.loader = BaseLoader.Loader(opts);

  const scope = this.loader.sharedGlobal;
  Cu.evalInSandbox(
    "function __syncImport(uri) { return ChromeUtils.importESModule(uri, {global: 'current'})}",
    scope
  );
  const syncImport = scope.__syncImport;

  // When running tests, expose the BrowserLoader instance for metrics tests.
  if (flags.testing) {
    window.getBrowserLoaderForWindow = () => this;
  }
  this.require = BaseLoader.Require(this.loader, mainModule);
  this.lazyRequireGetter = this.lazyRequireGetter.bind(this);
}

BrowserLoaderBuilder.prototype = {
  /**
   * Define a getter property on the given object that requires the given
   * module. This enables delaying importing modules until the module is
   * actually used.
   *
   * Several getters can be defined at once by providing an array of
   * properties and enabling destructuring.
   *
   * @param { Object } obj
   *    The object to define the property on.
   * @param { String | Array<String> } properties
   *    String: Name of the property for the getter.
   *    Array<String>: When destructure is true, properties can be an array of
   *    strings to create several getters at once.
   * @param { String } module
   *    The module path.
   * @param { Boolean } destructure
   *    Pass true if the property name is a member of the module's exports.
   */
  lazyRequireGetter(obj, properties, module, destructure) {
    if (Array.isArray(properties) && !destructure) {
      throw new Error(
        "Pass destructure=true to call lazyRequireGetter with an array of properties"
      );
    }

    if (!Array.isArray(properties)) {
      properties = [properties];
    }

    for (const property of properties) {
      loader.lazyGetter(obj, property, () => {
        return destructure
          ? this.require(module)[property]
          : this.require(module || property);
      });
    }
  },
};
