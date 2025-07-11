/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

const { Sqlite } = ChromeUtils.importESModule(
  "resource://gre/modules/Sqlite.sys.mjs"
);

add_task(async function () {
  let testnum = 0;

  try {
    // ===== test init =====
    let testfile = do_get_file("formhistory_v3.sqlite");
    let profileDir = Services.dirsvc.get("ProfD", Ci.nsIFile);

    // Cleanup from any previous tests or failures.
    let destFile = profileDir.clone();
    destFile.append("formhistory.sqlite");
    if (destFile.exists()) {
      destFile.remove(false);
    }

    testfile.copyTo(profileDir, "formhistory.sqlite");
    Assert.equal(3, await getDBVersion(testfile));

    Assert.ok(destFile.exists());

    // ===== 1 =====
    testnum++;

    destFile = profileDir.clone();
    destFile.append("formhistory.sqlite");
    let dbConnection = await Sqlite.openConnection({
      path: destFile.path,
      sharedMemoryCache: false,
    });

    // Do something that will cause FormHistory to access and upgrade the
    // database
    await FormHistory.count({});

    // check for upgraded schema.
    Assert.equal(CURRENT_SCHEMA, await getDBVersion(destFile));

    // Check that the index was added
    Assert.ok(dbConnection.tableExists("moz_deleted_formhistory"));
    dbConnection.close();

    // check for upgraded schema.
    Assert.equal(CURRENT_SCHEMA, await getDBVersion(destFile));

    // check that an entry still exists
    let num = await promiseCountEntries("name-A", "value-A");
    Assert.greater(num, 0);
  } catch (e) {
    throw new Error(`FAILED in test #${testnum} -- ${e}`);
  }
});
