/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.fruit.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.Intent.ShortcutIconResource;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;
import android.os.Process;
import android.os.SystemClock;
import android.provider.BaseColumns;
import android.provider.LiveFolders;

import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import com.fruit.launcher.LauncherSettings.Applications;
import com.fruit.launcher.LauncherSettings.BaseLauncherColumns;
import com.fruit.launcher.LauncherSettings.Favorites;
import com.fruit.launcher.setting.SettingUtils;
import com.fruit.launcher.theme.ThemeManager;
import com.fruit.launcher.theme.ThemeUtils;

/**
 * Maintains in-memory state of the Launcher. It is expected that there should
 * be only one LauncherModel object held in a static. Also provide APIs for
 * updating the database state for the Launcher.
 */
public class LauncherModel extends BroadcastReceiver {

	static final boolean DEBUG_LOADERS = true;
	static final boolean PROFILE_LOADERS = false;
	static final String TAG = "Launcher.Model";

	private int mBatchSize; // 0 is all apps at once
	private int mAllAppsLoadDelay; // milliseconds between batches

	private final LauncherApplication mApp;
	private final Object mLock = new Object();
	private DeferredHandler mHandler = new DeferredHandler();
	private Loader mLoader = new Loader();

	// We start off with everything not loaded. After that, we assume that
	// our monitoring of the package manager provides all updates and we never
	// need to do a requery. These are only ever touched from the loader thread.
	private boolean mWorkspaceLoaded;
	private boolean mAllAppsLoaded;

	private boolean mBeforeFirstLoad = true; // only access this from main
												// thread
	private WeakReference<Callbacks> mCallbacks;

	private final Object mAllAppsListLock = new Object();
	private AllAppsList mAllAppsList;
	private IconCache mIconCache;

	private Bitmap mDefaultIcon;

	public interface Callbacks {

		public int getCurrentWorkspaceScreen();

		public void startBinding();

		public void bindItems(ArrayList<ItemInfo> shortcuts, int start, int end);

		public void bindDockBarItems(ArrayList<ItemInfo> items);

		public void bindFolders(HashMap<Long, FolderInfo> folders);

		public void finishBindingItems();

		public void bindAppWidget(LauncherAppWidgetInfo info);

		public void bindCustomAppWidget(CustomAppWidgetInfo info);

		public void bindAllApplications(ArrayList<ApplicationInfo> apps);

		public void bindAppsAdded(ArrayList<ApplicationInfo> apps);

		public void bindAppsUpdated(ArrayList<ApplicationInfo> apps);

		public void bindAppsRemoved(ArrayList<ApplicationInfo> apps);

		public boolean isAllAppsVisible();

		public void removePackage(ArrayList<ApplicationInfo> apps);

		public void addPackage(ArrayList<ApplicationInfo> apps);

		public void updateAllApps();

		public void removeThemePackage(ArrayList<String> apps);
	}

	LauncherModel(LauncherApplication app, IconCache iconCache) {
		mApp = app;
		mAllAppsList = new AllAppsList(iconCache);
		mIconCache = iconCache;

		mDefaultIcon = Utilities.createIconBitmap(app.getPackageManager()
				.getDefaultActivityIcon(), app);

		mAllAppsLoadDelay = app.getResources().getInteger(
				R.integer.config_allAppsBatchLoadDelay);

		mBatchSize = app.getResources().getInteger(
				R.integer.config_allAppsBatchSize);
	}

	public Bitmap getFallbackIcon() {
		return Bitmap.createBitmap(mDefaultIcon);
	}

	/**
	 * Adds an item to the DB if it was not created previously, or move it to a
	 * new <container, screen, cellX, cellY>
	 */
	static void addOrMoveItemInDatabase(Context context, ItemInfo item,
			long container, int screen, int cellX, int cellY) {
		if (item.container == ItemInfo.NO_ID) {
			// From all apps
			addItemToDatabase(context, item, container, screen, cellX, cellY,
					false);
		} else {
			// From somewhere else
			moveItemInDatabase(context, item, container, screen, cellX, cellY);
		}
	}

	/**
	 * Move an item in the DB to a new <container, screen, cellX, cellY>
	 */
	static void moveItemInDatabase(Context context, ItemInfo item,
			long container, int screen, int cellX, int cellY) {
		item.container = container;
		item.screen = screen;
		item.cellX = cellX;
		item.cellY = cellY;

		final ContentValues values = new ContentValues();
		final ContentResolver cr = context.getContentResolver();

		values.put(Favorites.CONTAINER, item.container);
		values.put(Favorites.CELLX, item.cellX);
		values.put(Favorites.CELLY, item.cellY);
		values.put(Favorites.SCREEN, item.screen);
		values.put(BaseLauncherColumns.ORDERID, item.orderId);

		cr.update(Favorites.getContentUri(item.id, false), values, null, null);
	}

	/**
	 * Returns true if the shortcuts already exists in the database. we identify
	 * a shortcut by its title and intent.
	 */
	static boolean shortcutExists(Context context, String title, Intent intent) {
		final ContentResolver cr = context.getContentResolver();
		Cursor c = cr.query(Favorites.CONTENT_URI, new String[] { "title",
				"intent" }, "title=? and intent=?", new String[] { title,
				intent.toUri(0) }, null);
		boolean result = false;
		try {
			result = c.moveToFirst();
		} finally {
			c.close();
		}
		return result;
	}
	
	/**
	 * Find a folder in the db, creating the FolderInfo if necessary, and adding
	 * it to folderList.
	 */
	FolderInfo getFolderById(Context context,
			HashMap<Long, FolderInfo> folderList, long id) {
		final ContentResolver cr = context.getContentResolver();
		Cursor c = cr
				.query(Favorites.CONTENT_URI,
						null,
						"_id=? and (itemType=? or itemType=?)",
						new String[] {
								String.valueOf(id),
								String.valueOf(Favorites.ITEM_TYPE_USER_FOLDER),
								String.valueOf(Favorites.ITEM_TYPE_LIVE_FOLDER) },
						null);

		try {
			if (c.moveToFirst()) {
				final int itemTypeIndex = c
						.getColumnIndexOrThrow(BaseLauncherColumns.ITEM_TYPE);
				final int titleIndex = c
						.getColumnIndexOrThrow(BaseLauncherColumns.TITLE);
				final int containerIndex = c
						.getColumnIndexOrThrow(Favorites.CONTAINER);
				final int screenIndex = c
						.getColumnIndexOrThrow(Favorites.SCREEN);
				final int cellXIndex = c.getColumnIndexOrThrow(Favorites.CELLX);
				final int cellYIndex = c.getColumnIndexOrThrow(Favorites.CELLY);

				FolderInfo folderInfo = null;
				switch (c.getInt(itemTypeIndex)) {
				case Favorites.ITEM_TYPE_USER_FOLDER:
					folderInfo = findOrMakeUserFolder(folderList, id);
					break;
				case Favorites.ITEM_TYPE_LIVE_FOLDER:
					folderInfo = findOrMakeLiveFolder(folderList, id);
					break;
				}

				folderInfo.title = c.getString(titleIndex);
				folderInfo.id = id;
				folderInfo.container = c.getInt(containerIndex);
				folderInfo.screen = c.getInt(screenIndex);
				folderInfo.cellX = c.getInt(cellXIndex);
				folderInfo.cellY = c.getInt(cellYIndex);

				return folderInfo;
			}
		} finally {
			c.close();
		}

		return null;
	}

	/**
	 * Add an item to the database in a specified container. Sets the container,
	 * screen, cellX and cellY fields of the item. Also assigns an ID to the
	 * item.
	 */
	static void addItemToDatabase(Context context, ItemInfo item,
			long container, int screen, int cellX, int cellY, boolean notify) {
		item.container = container;
		item.screen = screen;
		item.cellX = cellX;
		item.cellY = cellY;

		final ContentValues values = new ContentValues();
		final ContentResolver cr = context.getContentResolver();

		item.onAddToDatabase(values);

		Uri result = cr.insert(notify ? Favorites.CONTENT_URI
				: Favorites.CONTENT_URI_NO_NOTIFICATION, values);

		if (result != null) {
			item.id = Integer.parseInt(result.getPathSegments().get(1));
		}
	}

	/**
	 * Update an item to the database in a specified container.
	 */
	static void updateItemInDatabase(Context context, ItemInfo item) {
		final ContentValues values = new ContentValues();
		final ContentResolver cr = context.getContentResolver();

		item.onAddToDatabase(values);

		cr.update(Favorites.getContentUri(item.id, false), values, null, null);
	}

	/**
	 * Removes the specified item from the database
	 * 
	 * @param context
	 * @param item
	 */
	static void deleteItemFromDatabase(Context context, ItemInfo item) {
		final ContentResolver cr = context.getContentResolver();

		cr.delete(Favorites.getContentUri(item.id, false), null, null);
	}

	/**
	 * Remove the contents of the specified folder from the database
	 */
	static void deleteUserFolderContentsFromDatabase(Context context,
			UserFolderInfo info) {
		final ContentResolver cr = context.getContentResolver();

		cr.delete(Favorites.getContentUri(info.id, false), null, null);
		cr.delete(Favorites.CONTENT_URI, Favorites.CONTAINER + "=" + info.id,
				null);
	}

	/**
	 * Set this as the current Launcher activity object for the loader.
	 */
	public void initialize(Callbacks callbacks) {
		synchronized (mLock) {
			mCallbacks = new WeakReference<Callbacks>(callbacks);
		}
	}

	public void startLoader(Context context, boolean isLaunching) {
		//if (!mWorkspaceLoaded)
			mLoader.startLoader(context, isLaunching);
	}

	public void stopLoader() {
		mLoader.stopLoader();
	}

//	public void createShortcutEx(Context context, Intent intent, String packageName) {
//		LauncherApplication app = (LauncherApplication) context;	
//		final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
//		mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);		
//		final PackageManager packageManager = context.getPackageManager();
//		List<ResolveInfo> apps = null;
//		apps = packageManager.queryIntentActivities(mainIntent, 0);
//
//		if (apps.size() == 0) {
//			return;
//		}
//
////		Collections.sort(apps, new ResolveInfo.DisplayNameComparator(
////				packageManager));
//
//		for (int j = 0; j < apps.size(); j++) {
//			// This builds the icon bitmaps.
//			ResolveInfo info = apps.get(j);
//
//			final android.content.pm.ApplicationInfo appInfo = info.activityInfo.applicationInfo;
//
//			//String intentInfo = "";
//			String infoName = info.activityInfo.name;
//
//			if (!appInfo.packageName.equals(packageName))
//				continue;
//			
//			Intent shortcutIntent = new Intent(InstallShortcutReceiver.ACTION_INSTALL_SHORTCUT);
//
//			shortcutIntent.putExtra("duplicate", false);
//
//			shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME,	info.loadLabel(packageManager).toString());
//			
//			ComponentName cn = new ComponentName(appInfo.packageName, infoName);
//			final IconCache ic = app.getIconCache();
//			shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON, ic.getIcon(cn, info));	
////			app.getResources().get
////			Parcelable icon = Intent.ShortcutIconResource.fromContext(app.getApplicationContext(), packageName+":drawable/icon"); 
////			shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, packageName+":drawable/icon");			
//			
//			Intent intent2 = new Intent();
//			intent2.setComponent(cn);
//			intent2.setAction("android.intent.action.MAIN");
//			intent2.addCategory("android.intent.category.LAUNCHER");
//			intent2.setFlags(0x10200000);
//			// intent2.setAction(intentInfo);
//			
//
//			shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent2);
//			
//			context.sendBroadcast(shortcutIntent);
//			break;
//		}
//	}

//	public void createShortcutEx2(Context context, Intent intent, String packageName) {
//		LauncherApplication app = (LauncherApplication) context;		
//		PackageManager pm = context.getPackageManager();
//
//		//Intent shortcutIntent = new Intent(Intent.ACTION_CREATE_SHORTCUT);
//		Intent shortcutIntent = new Intent(InstallShortcutReceiver.ACTION_INSTALL_SHORTCUT);
//		//Intent shortcutIntent = intent;
//		//intent.setAction(InstallShortcutReceiver.ACTION_INSTALL_SHORTCUT);
//		//shortcutIntent.putExtra("duplicate", false);
//		
//		Intent intent2 = new Intent();
//		intent2.setComponent(new ComponentName(packageName, packageName));
//		intent2.setAction("android.intent.action.MAIN");
//		intent2.addCategory("android.intent.category.LAUNCHER");
//		intent2.setFlags(0x10200000);
//		
//		shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent2);
//		shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, packageName);
//		shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON, app.getIconCache().getIcon(intent));
//		//shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, icon);
//		
//		context.sendBroadcast(shortcutIntent);
//	}
	
	/**
	 * Call from the handler for ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REMOVED
	 * and ACTION_PACKAGE_CHANGED.
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		// Use the app as the context.
		context = mApp;

		ArrayList<ApplicationInfo> added = null;
		ArrayList<ApplicationInfo> removed = null;
		ArrayList<ApplicationInfo> modified = null;
		ArrayList<String> themeRemoved = null;
		final String action = intent.getAction();
		if (mBeforeFirstLoad) {
			// If we haven't even loaded yet, don't bother, since we'll just
			// pick up the changes.
			if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
				Log.e(TAG,
						"onReceive, Before First Load, ACTION_EXTERNAL_APPLICATIONS_AVAILABLE");
			} else {
				Log.e(TAG, "Before First Load, action=" + action + ", retrun!");
				return;
			}
		}

		synchronized (mAllAppsListLock) {
			Log.e(TAG, "LauncherModel onReceive aciton =" + action);
			if (Intent.ACTION_PACKAGE_CHANGED.equals(action)
					|| Intent.ACTION_PACKAGE_REMOVED.equals(action)
					|| Intent.ACTION_PACKAGE_ADDED.equals(action)) {
				final String packageName = intent.getData()
						.getSchemeSpecificPart();
				final boolean replacing = intent.getBooleanExtra(
						Intent.EXTRA_REPLACING, false);

				if (packageName == null || packageName.length() == 0) {
					// they sent us a bad intent
					return;
				}

				if (Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
					mAllAppsList.updatePackage(context, packageName);
				} else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
					if (!replacing) {
						mAllAppsList.removePackage(packageName);
					}

					if (packageName != null
							&& packageName
									.startsWith(ThemeUtils.THEME_PACKAGE_TOKEN)) {
						themeRemoved = new ArrayList<String>();
						themeRemoved.add(new String(packageName));
					}
					// else, we are replacing the package, so a PACKAGE_ADDED
					// will be sent
					// later, we will update the package at this time
				} else if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
					if (!replacing) {
						mAllAppsList.addPackage(context, packageName);
					} else {
						mAllAppsList.updatePackage(context, packageName);
					}
				}

				if (mAllAppsList.added.size() > 0) {
					added = mAllAppsList.added;
					mAllAppsList.added = new ArrayList<ApplicationInfo>();
				}
				if (mAllAppsList.removed.size() > 0) {
					removed = mAllAppsList.removed;
					mAllAppsList.removed = new ArrayList<ApplicationInfo>();
					for (ApplicationInfo info : removed) {
						mIconCache.remove(info.intent.getComponent());
					}
				}
				if (mAllAppsList.modified.size() > 0) {
					modified = mAllAppsList.modified;
					mAllAppsList.modified = new ArrayList<ApplicationInfo>();
				}

				final Callbacks callbacks = mCallbacks != null ? mCallbacks
						.get() : null;
				if (callbacks == null) {
					Log.w(TAG,
							"Nobody to tell about the new app.  Launcher is probably loading.");
					return;
				}

				if (added != null) {
					final ArrayList<ApplicationInfo> addedFinal = added;
					mHandler.post(new Runnable() {
						@Override
						public void run() {
							callbacks.bindAppsAdded(addedFinal);
							callbacks.addPackage(addedFinal);
						}
					});
					//createShortcutEx(context, intent, packageName); // yfzhao
				}
				if (modified != null) {
					final ArrayList<ApplicationInfo> modifiedFinal = modified;
					mHandler.post(new Runnable() {
						@Override
						public void run() {
							callbacks.bindAppsUpdated(modifiedFinal);
						}
					});
					//createShortcutEx(context, intent, packageName); // yfzhao
					// createShortcutEx(context, intent); //yfzhao
					// Toast.makeText(context,
					// context.getString(R.string.app_updated),
					// Toast.LENGTH_SHORT).show();
				}
				if (removed != null) {
					final ArrayList<ApplicationInfo> removedFinal = removed;
					mHandler.post(new Runnable() {
						@Override
						public void run() {
							callbacks.removePackage(removedFinal);
							callbacks.bindAppsRemoved(removedFinal);
						}
					});
				}
				if (themeRemoved != null) {
					final ArrayList<String> removedFinal = themeRemoved;
					mHandler.post(new Runnable() {
						@Override
						public void run() {
							callbacks.removeThemePackage(removedFinal);
						}
					});
				}
			} else {
				if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
						.equals(action)) {
					String packages[] = intent
							.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
					if (DEBUG_LOADERS)
						Log.d(TAG,
								"ACTION_EXTERNAL_APPLICATIONS_AVAILABLE, size="
										+ packages.length);
					if (packages == null || packages.length == 0) {
						return;
					}
					if (DEBUG_LOADERS) {
						for (String app : packages) {
							Log.d(TAG, "package:" + app);
						}
					}
					synchronized (this) {
						mAllAppsLoaded = mWorkspaceLoaded = false;
					}
					Log.d(TAG, "onReceive(), startLoader,false, Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE");
					startLoader(context, false);
				} else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE
						.equals(action)) {
					String packages[] = intent
							.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
					if (packages == null || packages.length == 0) {
						return;
					}
					synchronized (this) {
						mAllAppsLoaded = mWorkspaceLoaded = false;
					}
					Log.d(TAG, "onReceive(), startLoader,false, Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE");
					startLoader(context, false);
				}
			}
		}
	}

	public class Loader {
		private static final int ITEMS_CHUNK = 6;

		private LoaderThread mLoaderThread;

		final ArrayList<ItemInfo> mItems = new ArrayList<ItemInfo>();
		final ArrayList<ItemInfo> mDockBarItems = new ArrayList<ItemInfo>();
		final ArrayList<LauncherAppWidgetInfo> mAppWidgets = new ArrayList<LauncherAppWidgetInfo>();
		final ArrayList<CustomAppWidgetInfo> mCustomAppWidgets = new ArrayList<CustomAppWidgetInfo>();
		final HashMap<Long, FolderInfo> mFolders = new HashMap<Long, FolderInfo>();

		/**
		 * Call this from the ui thread so the handler is initialized on the
		 * correct thread.
		 */
		public Loader() {

		}

		public void startLoader(Context context, boolean isLaunching) {
			synchronized (mLock) {
				if (DEBUG_LOADERS) {
					Log.d(TAG, "startLoader isLaunching=" + isLaunching);
				}

				// Don't bother to start the thread if we know it's not going to
				// do anything
				if (mCallbacks != null && mCallbacks.get() != null) {
					LoaderThread oldThread = mLoaderThread;
					if (oldThread != null) {
						if (oldThread.isLaunching()) {
							// don't downgrade isLaunching if we're already
							// running
							isLaunching = true;
						}
						oldThread.stopLocked();
					}
					mHandler.cancel();

					mLoaderThread = new LoaderThread(context, oldThread,
							isLaunching);
					mLoaderThread.start();
				}
			}
		}

		public void stopLoader() {
			synchronized (mLock) {
				if (mLoaderThread != null) {
					mLoaderThread.stopLocked();
				}
			}
		}

		/**
		 * Runnable for the thread that loads the contents of the launcher: -
		 * workspace icons - widgets - all apps icons
		 */
		private class LoaderThread extends Thread {
			private Context mContext;
			private Thread mWaitThread;
			private boolean mIsLaunching;
			private boolean mStopped;
			private boolean mLoadAndBindStepFinished;

			LoaderThread(Context context, Thread waitThread, boolean isLaunching) {
				mContext = context;
				mWaitThread = waitThread;
				mIsLaunching = isLaunching;
			}

			boolean isLaunching() {
				return mIsLaunching;
			}

			/**
			 * If another LoaderThread was supplied, we need to wait for that to
			 * finish before we start our processing. This keeps the ordering of
			 * the setting and clearing of the dirty flags correct by making
			 * sure we don't start processing stuff until they've had a chance
			 * to re-set them. We do this waiting the worker thread, not the ui
			 * thread to avoid ANRs.
			 */
			private void waitForOtherThread() {
				if (mWaitThread != null) {
					boolean done = false;
					while (!done) {
						try {
							mWaitThread.join();
							done = true;
						} catch (InterruptedException ex) {
							// Ignore
						}
					}
					mWaitThread = null;
				}
			}

			private void loadAndBindWorkspace() {
				// Load the workspace

				// Other other threads can unset mWorkspaceLoaded, so atomically
				// set it,
				// and then if they unset it, or we unset it because of
				// mStopped, it will
				// be unset.
				boolean loaded;
				synchronized (this) {
					loaded = mWorkspaceLoaded;
					mWorkspaceLoaded = true;
				}

				// For now, just always reload the workspace. It's ~100 ms vs.
				// the
				// binding which takes many hundreds of ms.
				// We can reconsider.
				if (DEBUG_LOADERS) {
					Log.d(TAG, "loadAndBindWorkspace loaded=" + loaded);
				}
				// if (true || !loaded) {
				if (true) {
					loadWorkspace();
					if (mStopped) {
						mWorkspaceLoaded = false;
						return;
					}
				}

				// Bind the workspace
				bindWorkspace();
			}

			@SuppressWarnings("unused")
			private void waitForIdle() {
				// Wait until the either we're stopped or the other threads are
				// done.
				// This way we don't start loading all apps until the workspace
				// has settled
				// down.
				synchronized (LoaderThread.this) {
					final long workspaceWaitTime = DEBUG_LOADERS ? SystemClock
							.uptimeMillis() : 0;

					mHandler.postIdle(new Runnable() {
						@Override
						public void run() {
							synchronized (LoaderThread.this) {
								mLoadAndBindStepFinished = true;
								if (DEBUG_LOADERS) {
									Log.d(TAG,
											"done with previous binding step");
								}
								LoaderThread.this.notify();
							}
						}
					});

					while (!mStopped && !mLoadAndBindStepFinished) {
						try {
							this.wait();
						} catch (InterruptedException ex) {
							// Ignore
						}
					}
					if (DEBUG_LOADERS) {
						Log.d(TAG,
								"waited "
										+ (SystemClock.uptimeMillis() - workspaceWaitTime)
										+ "ms for previous step to finish binding");
					}
				}
			}

			@Override
			public void run() {
				waitForOtherThread();

				// Optimize for end-user experience: if the Launcher is up and
				// // running with the
				// All Apps interface in the foreground, load All Apps first.
				// Otherwise, load the
				// workspace first (default).
				final Callbacks cbk = mCallbacks.get();
				final boolean loadWorkspaceFirst = cbk != null ? (!cbk
						.isAllAppsVisible()) : true;

				// Elevate priority when Home launches for the first time to
				// avoid
				// starving at boot time. Staring at a blank home is not cool.
				synchronized (mLock) {
					android.os.Process
							.setThreadPriority(mIsLaunching ? Process.THREAD_PRIORITY_DEFAULT
									: Process.THREAD_PRIORITY_BACKGROUND);
				}

				if (PROFILE_LOADERS) {
					android.os.Debug
							.startMethodTracing("/sdcard/launcher-loaders");
				}

				if (loadWorkspaceFirst) {
					if (DEBUG_LOADERS) {
						Log.d(TAG, "step 1: loading workspace");
					}
					loadAndBindWorkspace();
				} else {
					if (DEBUG_LOADERS) {
						Log.d(TAG, "step 1: special: loading all apps");
					}
					loadAndBindAllApps();
				}

				// Whew! Hard work done.
				synchronized (mLock) {
					if (mIsLaunching) {
						android.os.Process
								.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
					}
				}

				// second step
				if (loadWorkspaceFirst) {
					if (DEBUG_LOADERS) {
						Log.d(TAG, "step 2: loading all apps");
					}
					loadAndBindAllApps();
				} else {
					if (DEBUG_LOADERS) {
						Log.d(TAG, "step 2: special: loading workspace");
					}
					loadAndBindWorkspace();
				}

				// Clear out this reference, otherwise we end up holding it
				// until all of the
				// callback runnables are done.
				mContext = null;

				synchronized (mLock) {
					// Setting the reference is atomic, but we can't do it
					// inside the other critical
					// sections.
					mLoaderThread = null;
				}

				if (PROFILE_LOADERS) {
					android.os.Debug.stopMethodTracing();
				}

				// Trigger a gc to try to clean up after the stuff is done,
				// since the
				// renderscript allocations aren't charged to the java heap.
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						System.gc();
					}
				});
			}

			public void stopLocked() {
				synchronized (LoaderThread.this) {
					mStopped = true;
					mHandler.cancel();
					this.notify();
				}
			}

			/**
			 * Gets the callbacks object. If we've been stopped, or if the
			 * launcher object has somehow been garbage collected, return null
			 * instead. Pass in the Callbacks object that was around when the
			 * deferred message was scheduled, and if there's a new Callbacks
			 * object around then also return null. This will save us from
			 * calling onto it with data that will be ignored.
			 */
			Callbacks tryGetCallbacks(Callbacks oldCallbacks) {
				synchronized (mLock) {
					if (mStopped) {
						return null;
					}

					if (mCallbacks == null) {
						return null;
					}

					final Callbacks callbacks = mCallbacks.get();
					if (callbacks != oldCallbacks) {
						return null;
					}
					if (callbacks == null) {
						Log.w(TAG, "no mCallbacks");
						return null;
					}

					return callbacks;
				}
			}

			// check & update map of what's occupied; used to discard
			// overlapping/invalid items
			private boolean checkItemPlacement(ItemInfo occupied[][][],
					ItemInfo item, Context context) {
				boolean result = checkItemPlacement(occupied, item);
				if (!result){
					deleteItemFromDatabase(context, item);
				}
				return result;
			}
			
			// check & update map of what's occupied; used to discard
			// overlapping/invalid items
			private boolean checkItemPlacement(ItemInfo occupied[][][],
					ItemInfo item) {
				if (item.container != Favorites.CONTAINER_DESKTOP) {
					return true;
				}

				for (int x = item.cellX; x < (item.cellX + item.spanX); x++) {
					for (int y = item.cellY; y < (item.cellY + item.spanY); y++) {
						if (occupied[item.screen][x][y] != null) {
							Log.e(TAG, "Error loading shortcut " + item
									+ " into cell (" + item.screen + ":" + x
									+ "," + y + ") occupied by "
									+ occupied[item.screen][x][y]);
							return false;
						}
					}
				}
				for (int x = item.cellX; x < (item.cellX + item.spanX); x++) {
					for (int y = item.cellY; y < (item.cellY + item.spanY); y++) {
						occupied[item.screen][x][y] = item;
					}
				}
				return true;
			}

			private void checkScreenCount(ContentResolver cr, Context context){		
				int screen = SettingUtils.mScreenCount;
				
				final Cursor cursor = cr.query(Favorites.CONTENT_URI,
						new String[] {Favorites.SCREEN}, null, null, Favorites.SCREEN + " DESC");				
				
				try {			
					if (cursor != null && cursor.moveToFirst()) {
						int maxScreenIndex = cursor.getInt(0);//cursor.getColumnIndexOrThrow(Favorites.SCREEN);
						
						if (maxScreenIndex > SettingUtils.MAX_SCREEN_COUNT-1)
							maxScreenIndex = SettingUtils.MAX_SCREEN_COUNT-1;
						
						if (maxScreenIndex>=screen){
							SettingUtils.mScreenCount=maxScreenIndex+1;
							SettingUtils.saveScreenSettings(context);
						}
					}			
					
				} catch (Exception e) {
					// TODO Auto-generated catch block					
					e.printStackTrace();
					SettingUtils.mScreenCount=screen;					
				} finally{
					cursor.close();
				}
			}
			
			private void loadWorkspace() {
				final long t = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;

				final Context context = mContext;
				final ContentResolver contentResolver = context
						.getContentResolver();
				final PackageManager manager = context.getPackageManager();
				final AppWidgetManager widgets = AppWidgetManager
						.getInstance(context);
				final boolean isSafeMode = manager.isSafeMode();

				mItems.clear();
				mDockBarItems.clear();
				mAppWidgets.clear();
				mCustomAppWidgets.clear();
				mFolders.clear();

				final ArrayList<Long> itemsToRemove = new ArrayList<Long>();

				final Cursor c = contentResolver.query(Favorites.CONTENT_URI,
						null, null, null, null);

				checkScreenCount(contentResolver, context);
				
				final ItemInfo occupied[][][] = new ItemInfo[SettingUtils.mScreenCount][Launcher.NUMBER_CELLS_X][Launcher.NUMBER_CELLS_Y];

				try {
					final int idIndex = c
							.getColumnIndexOrThrow(BaseColumns._ID);
					final int intentIndex = c
							.getColumnIndexOrThrow(BaseLauncherColumns.INTENT);
					final int titleIndex = c
							.getColumnIndexOrThrow(BaseLauncherColumns.TITLE);
					final int iconTypeIndex = c
							.getColumnIndexOrThrow(BaseLauncherColumns.ICON_TYPE);
					final int iconIndex = c
							.getColumnIndexOrThrow(BaseLauncherColumns.ICON);
					final int iconPackageIndex = c
							.getColumnIndexOrThrow(BaseLauncherColumns.ICON_PACKAGE);
					final int iconResourceIndex = c
							.getColumnIndexOrThrow(BaseLauncherColumns.ICON_RESOURCE);
					final int containerIndex = c
							.getColumnIndexOrThrow(Favorites.CONTAINER);
					final int orderIdIndex = c
							.getColumnIndexOrThrow(BaseLauncherColumns.ORDERID);
					final int itemTypeIndex = c
							.getColumnIndexOrThrow(BaseLauncherColumns.ITEM_TYPE);
					final int appWidgetIdIndex = c
							.getColumnIndexOrThrow(Favorites.APPWIDGET_ID);
					final int screenIndex = c
							.getColumnIndexOrThrow(Favorites.SCREEN);
					final int cellXIndex = c
							.getColumnIndexOrThrow(Favorites.CELLX);
					final int cellYIndex = c
							.getColumnIndexOrThrow(Favorites.CELLY);
					final int spanXIndex = c
							.getColumnIndexOrThrow(Favorites.SPANX);
					final int spanYIndex = c
							.getColumnIndexOrThrow(Favorites.SPANY);
					final int uriIndex = c.getColumnIndexOrThrow(Favorites.URI);
					final int displayModeIndex = c
							.getColumnIndexOrThrow(Favorites.DISPLAY_MODE);

					ShortcutInfo info;
					String intentDescription;
					LauncherAppWidgetInfo appWidgetInfo;
					int container;
					long id;
					Intent intent;

					while (!mStopped && c.moveToNext()) {
						try {
							int itemType = c.getInt(itemTypeIndex);

							switch (itemType) {
							case BaseLauncherColumns.ITEM_TYPE_APPLICATION:
							case BaseLauncherColumns.ITEM_TYPE_SHORTCUT:
								intentDescription = c.getString(intentIndex);
								try {
									intent = Intent.parseUri(intentDescription,
											0);
								} catch (URISyntaxException e) {
									continue;
								}

								if (itemType == BaseLauncherColumns.ITEM_TYPE_APPLICATION) {
									info = getShortcutInfo(manager, intent,
											context, c, iconIndex, titleIndex);
								} else {
									info = getShortcutInfo(c, context,
											iconTypeIndex, iconPackageIndex,
											iconResourceIndex, iconIndex,
											titleIndex);
								}

								if (info != null) {
									updateSavedIcon(context, info, c, iconIndex);

									info.intent = intent;
									info.id = c.getLong(idIndex);
									container = c.getInt(containerIndex);
									info.container = container;
									info.screen = c.getInt(screenIndex);
									info.cellX = c.getInt(cellXIndex);
									info.cellY = c.getInt(cellYIndex);
									info.orderId = c.getInt(orderIdIndex);

									// check & update map of what's occupied
									if (!checkItemPlacement(occupied, info)) {
										break;
									}

									switch (container) {
									case Favorites.CONTAINER_DESKTOP:
										mItems.add(info);
										break;
									case Favorites.CONTAINER_DOCKBAR:
										mDockBarItems.add(info);
										break;
									default:
										// Item is in a user folder
										UserFolderInfo folderInfo = findOrMakeUserFolder(
												mFolders, container);
										folderInfo.add(info);
										break;
									}
								} else {
									// Failed to load the shortcut, probably
									// because the
									// activity manager couldn't resolve it
									// (maybe the app
									// was uninstalled), or the db row was
									// somehow screwed up.
									// Delete it.
									id = c.getLong(idIndex);
									Log.e(TAG, "Error loading shortcut " + id
											+ ", removing it");
									contentResolver.delete(
											Favorites.getContentUri(id, false),
											null, null);
								}
								break;

							case Favorites.ITEM_TYPE_USER_FOLDER:
								id = c.getLong(idIndex);
								UserFolderInfo folderInfo = findOrMakeUserFolder(
										mFolders, id);

								folderInfo.title = c.getString(titleIndex);

								folderInfo.id = id;
								container = c.getInt(containerIndex);
								folderInfo.container = container;
								folderInfo.screen = c.getInt(screenIndex);
								folderInfo.cellX = c.getInt(cellXIndex);
								folderInfo.cellY = c.getInt(cellYIndex);

								// check & update map of what's occupied
								if (!checkItemPlacement(occupied, folderInfo)) {
									break;
								}

								switch (container) {
								case Favorites.CONTAINER_DESKTOP:
									mItems.add(folderInfo);
									break;
								}

								mFolders.put(folderInfo.id, folderInfo);
								break;

							case Favorites.ITEM_TYPE_LIVE_FOLDER:
								id = c.getLong(idIndex);
								Uri uri = Uri.parse(c.getString(uriIndex));

								// Make sure the live folder exists
								final ProviderInfo providerInfo = context
										.getPackageManager()
										.resolveContentProvider(
												uri.getAuthority(), 0);

								if (providerInfo == null && !isSafeMode) {
									itemsToRemove.add(id);
								} else {
									LiveFolderInfo liveFolderInfo = findOrMakeLiveFolder(
											mFolders, id);

									intentDescription = c
											.getString(intentIndex);
									intent = null;
									if (intentDescription != null) {
										try {
											intent = Intent.parseUri(
													intentDescription, 0);
										} catch (URISyntaxException e) {
											// Ignore, a live folder might not
											// have a base intent
										}
									}

									liveFolderInfo.title = c
											.getString(titleIndex);
									liveFolderInfo.id = id;
									liveFolderInfo.uri = uri;
									container = c.getInt(containerIndex);
									liveFolderInfo.container = container;
									liveFolderInfo.screen = c
											.getInt(screenIndex);
									liveFolderInfo.cellX = c.getInt(cellXIndex);
									liveFolderInfo.cellY = c.getInt(cellYIndex);
									liveFolderInfo.baseIntent = intent;
									liveFolderInfo.displayMode = c
											.getInt(displayModeIndex);

									// check & update map of what's occupied
									if (!checkItemPlacement(occupied,
											liveFolderInfo)) {
										break;
									}

									loadLiveFolderIcon(context, c,
											iconTypeIndex, iconPackageIndex,
											iconResourceIndex, liveFolderInfo);

									switch (container) {
									case Favorites.CONTAINER_DESKTOP:
										mItems.add(liveFolderInfo);
										break;
									}
									mFolders.put(liveFolderInfo.id,
											liveFolderInfo);
								}
								break;

							case Favorites.ITEM_TYPE_APPWIDGET:
								// Read all Launcher-specific widget details
								int appWidgetId = c.getInt(appWidgetIdIndex);
								id = c.getLong(idIndex);

								final AppWidgetProviderInfo provider = widgets
										.getAppWidgetInfo(appWidgetId);

								if (!isSafeMode
										&& (provider == null
												|| provider.provider == null || provider.provider
												.getPackageName() == null)) {
									Log.e(TAG,
											"Deleting widget that isn't installed anymore: id="
													+ id + " appWidgetId="
													+ appWidgetId);
									itemsToRemove.add(id);
								} else {
									appWidgetInfo = new LauncherAppWidgetInfo(
											appWidgetId);
									appWidgetInfo.id = id;
									appWidgetInfo.screen = c
											.getInt(screenIndex);
									appWidgetInfo.cellX = c.getInt(cellXIndex);
									appWidgetInfo.cellY = c.getInt(cellYIndex);
									appWidgetInfo.spanX = c.getInt(spanXIndex);
									appWidgetInfo.spanY = c.getInt(spanYIndex);

									container = c.getInt(containerIndex);
									if (container != Favorites.CONTAINER_DESKTOP) {
										Log.e(TAG,
												"Widget found where container "
														+ "!= CONTAINER_DESKTOP -- ignoring!");
										continue;
									}
									appWidgetInfo.container = c
											.getInt(containerIndex);

									// check & update map of what's occupied
									if (!checkItemPlacement(occupied,
											appWidgetInfo, context)) {
										break;
									}

									mAppWidgets.add(appWidgetInfo);
								}
								break;

							case Favorites.ITEM_TYPE_WIDGET_LOCK_SCREEN:
							case Favorites.ITEM_TYPE_WIDGET_CLEAN_MEMORY:
								CustomAppWidgetInfo customAppWidgetInfo = CustomAppWidgetInfo
										.getWidgetInfoByType(itemType);
								customAppWidgetInfo.id = c.getLong(idIndex);
								customAppWidgetInfo.screen = c
										.getInt(screenIndex);
								customAppWidgetInfo.cellX = c
										.getInt(cellXIndex);
								customAppWidgetInfo.cellY = c
										.getInt(cellYIndex);

								container = c.getInt(containerIndex);
								if (container != Favorites.CONTAINER_DESKTOP) {
									Log.e(TAG,
											"Widget found where container "
													+ "!= CONTAINER_DESKTOP -- ignoring!");
									continue;
								}
								customAppWidgetInfo.container = container;

								// check & update map of what's occupied
								if (!checkItemPlacement(occupied,
										customAppWidgetInfo)) {
									break;
								}
								mCustomAppWidgets.add(customAppWidgetInfo);
								break;
							}
						} catch (Exception e) {
							Log.w(TAG, "Desktop items loading interrupted:", e);
						}
					}
				} finally {
					c.close();
				}

				if (itemsToRemove.size() > 0) {
					ContentProviderClient client = contentResolver
							.acquireContentProviderClient(Favorites.CONTENT_URI);
					// Remove dead items
					for (long id : itemsToRemove) {
						if (DEBUG_LOADERS) {
							Log.d(TAG, "Removed id = " + id);
						}
						// Don't notify content observers
						try {
							client.delete(Favorites.getContentUri(id, false),
									null, null);
						} catch (RemoteException e) {
							Log.w(TAG, "Could not remove id = " + id);
						}
					}
				}

				if (DEBUG_LOADERS) {
					Log.d(TAG,
							"loaded workspace in "
									+ (SystemClock.uptimeMillis() - t) + "ms");
					Log.d(TAG, "workspace layout: ");
					for (int y = 0; y < Launcher.NUMBER_CELLS_Y; y++) {
						String line = "";
						for (int s = 0; s < SettingUtils.mScreenCount; s++) {
							if (s > 0) {
								line += " | ";
							}
							for (int x = 0; x < Launcher.NUMBER_CELLS_X; x++) {
								line += ((occupied[s][x][y] != null) ? "#"
										: ".");
							}
						}
						Log.d(TAG, "[ " + line + " ]");
					}
				}
			}

			/**
			 * Read everything out of our database.
			 */
			private void bindWorkspace() {
				final long t = SystemClock.uptimeMillis();

				// Don't use these two variables in any of the callback
				// runnables.
				// Otherwise we hold a reference to them.
				final Callbacks oldCallbacks = mCallbacks.get();
				if (oldCallbacks == null) {
					// This launcher has exited and nobody bothered to tell us.
					// Just bail.
					Log.w(TAG, "LoaderThread running with no launcher");
					return;
				}

				int N;
				// Tell the workspace that we're about to start firing items at
				// it
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						Callbacks callbacks = tryGetCallbacks(oldCallbacks);
						if (callbacks != null) {
							callbacks.startBinding();
						}
					}
				});
				// Add the items to the workspace.
				N = mItems.size();
				for (int i = 0; i < N; i += ITEMS_CHUNK) {
					final int start = i;
					final int chunkSize = ((i + ITEMS_CHUNK) <= N) ? ITEMS_CHUNK
							: (N - i);
					mHandler.post(new Runnable() {
						@Override
						public void run() {
							if (DEBUG_LOADERS) {
								Log.d(TAG,
										"bindWorkspace, bindItems, mStopped="
												+ mStopped + ", this=" + this);
							}
							Callbacks callbacks = tryGetCallbacks(oldCallbacks);
							if (callbacks != null) {
								callbacks.bindItems(mItems, start, start
										+ chunkSize);
							}
						}
					});
				}
				// Bind Dock Bar items
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						Callbacks callbacks = tryGetCallbacks(oldCallbacks);
						if (callbacks != null) {
							callbacks.bindDockBarItems(mDockBarItems);
						}
					}
				});
				// Wait until the queue goes empty.
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						if (DEBUG_LOADERS) {
							Log.d(TAG, "Going to start binding folders soon.");
						}
					}
				});
				// Bind folders
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						Callbacks callbacks = tryGetCallbacks(oldCallbacks);
						if (callbacks != null) {
							callbacks.bindFolders(mFolders);
						}
					}
				});
				// Wait until the queue goes empty.
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						if (DEBUG_LOADERS) {
							Log.d(TAG, "Going to start binding widgets soon.");
						}
					}
				});
				// Bind the widgets, one at a time.
				// WARNING: this is calling into the workspace from the
				// background thread,
				// but since getCurrentScreen() just returns the int, we should
				// be okay. This
				// is just a hint for the order, and if it's wrong, we'll be
				// okay.
				// TODO: instead, we should have that push the current screen
				// into here.
				final int currentScreen = oldCallbacks
						.getCurrentWorkspaceScreen();
				N = mAppWidgets.size();
				// once for the current screen
				for (int i = 0; i < N; i++) {
					final LauncherAppWidgetInfo widget = mAppWidgets.get(i);
					if (widget.screen == currentScreen) {
						mHandler.post(new Runnable() {
							@Override
							public void run() {
								Callbacks callbacks = tryGetCallbacks(oldCallbacks);
								if (callbacks != null) {
									callbacks.bindAppWidget(widget);
								}
							}
						});
					}
				}
				// once for the other screens
				for (int i = 0; i < N; i++) {
					final LauncherAppWidgetInfo widget = mAppWidgets.get(i);
					if (widget.screen != currentScreen) {
						mHandler.post(new Runnable() {
							@Override
							public void run() {
								Callbacks callbacks = tryGetCallbacks(oldCallbacks);
								if (callbacks != null) {
									callbacks.bindAppWidget(widget);
								}
							}
						});
					}
				}
				// Bind the LauncherHQ custom widgets, one at a time.
				// WARNING: this is calling into the workspace from the
				// background thread,
				// but since getCurrentScreen() just returns the int, we should
				// be okay. This
				// is just a hint for the order, and if it's wrong, we'll be
				// okay.
				// TODO: instead, we should have that push the current screen
				// into here.
				N = mCustomAppWidgets.size();
				// once for the current screen
				for (int i = 0; i < N; i++) {
					final CustomAppWidgetInfo widget = mCustomAppWidgets.get(i);
					if (widget.screen == currentScreen) {
						mHandler.post(new Runnable() {
							@Override
							public void run() {
								Callbacks callbacks = tryGetCallbacks(oldCallbacks);
								if (callbacks != null) {
									callbacks.bindCustomAppWidget(widget);
								}
							}
						});
					}
				}
				// once for the other screens
				for (int i = 0; i < N; i++) {
					final CustomAppWidgetInfo widget = mCustomAppWidgets.get(i);
					if (widget.screen != currentScreen) {
						mHandler.post(new Runnable() {
							@Override
							public void run() {
								Callbacks callbacks = tryGetCallbacks(oldCallbacks);
								if (callbacks != null) {
									callbacks.bindCustomAppWidget(widget);
								}
							}
						});
					}
				}
				// Tell the workspace that we're done.
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						Callbacks callbacks = tryGetCallbacks(oldCallbacks);
						if (callbacks != null) {
							callbacks.finishBindingItems();
						}
					}
				});
				// If we're profiling, this is the last thing in the queue.
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						if (DEBUG_LOADERS) {
							Log.d(TAG,
									"bound workspace in "
											+ (SystemClock.uptimeMillis() - t)
											+ "ms");
						}
					}
				});
			}

			private void loadAndBindAllApps() {
				// Other other threads can unset mAllAppsLoaded, so atomically
				// set it,
				// and then if they unset it, or we unset it because of
				// mStopped, it will
				// be unset.
				boolean loaded;
				synchronized (this) {
					loaded = mAllAppsLoaded;
					mAllAppsLoaded = true;
				}

				if (DEBUG_LOADERS) {
					Log.d(TAG, "loadAndBindAllApps loaded=" + loaded);
				}

				if (!loaded) {
					loadAllAppsByBatch();
					if (mStopped) {
						mAllAppsLoaded = false;
						return;
					}
				} else {
					onlyBindAllApps();
				}

				updateSlideData(mContext);
			}

			private void onlyBindAllApps() {
				final Callbacks oldCallbacks = mCallbacks.get();
				if (oldCallbacks == null) {
					// This launcher has exited and nobody bothered to tell us.
					// Just bail.
					Log.w(TAG,
							"LoaderThread running with no launcher (onlyBindAllApps)");
					return;
				}

				// shallow copy
				final ArrayList<ApplicationInfo> list = (ArrayList<ApplicationInfo>) mAllAppsList.data
						.clone();
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						final long t = SystemClock.uptimeMillis();
						final Callbacks callbacks = tryGetCallbacks(oldCallbacks);
						if (callbacks != null) {
							callbacks.bindAllApplications(list);
						}
						if (DEBUG_LOADERS) {
							Log.d(TAG,
									"bound all " + list.size()
											+ " apps from cache in "
											+ (SystemClock.uptimeMillis() - t)
											+ "ms");
						}
					}
				});

			}

			private void loadAllAppsByBatch() {
				final long t = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;

				// Don't use these two variables in any of the callback
				// runnables.
				// Otherwise we hold a reference to them.
				final Callbacks oldCallbacks = mCallbacks.get();
				if (oldCallbacks == null) {
					// This launcher has exited and nobody bothered to tell us.
					// Just bail.
					Log.w(TAG,
							"LoaderThread running with no launcher (loadAllAppsByBatch)");
					return;
				}

				final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
				mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

				final PackageManager packageManager = mContext
						.getPackageManager();
				List<ResolveInfo> apps = null;

				int N = Integer.MAX_VALUE;

				int startIndex;
				int i = 0;
				int batchSize = -1;
				while (i < N && !mStopped) {
					synchronized (mAllAppsListLock) {
						if (i == 0) {
							// This needs to happen inside the same lock block
							// as when we
							// prepare the first batch for bindAllApplications.
							// Otherwise
							// the package changed receiver can come in and
							// double-add
							// (or miss one?).
							mAllAppsList.clear();
							final long qiaTime = DEBUG_LOADERS ? SystemClock
									.uptimeMillis() : 0;
							apps = packageManager.queryIntentActivities(
									mainIntent, 0);
							if (DEBUG_LOADERS) {
								Log.d(TAG,
										"queryIntentActivities took "
												+ (SystemClock.uptimeMillis() - qiaTime)
												+ "ms");
							}
							if (apps == null) {
								return;
							}
							N = apps.size();
							if (DEBUG_LOADERS) {
								Log.d(TAG, "queryIntentActivities got " + N
										+ " apps");
							}
							if (N == 0) {
								// There are no apps?!?
								return;
							}
							if (mBatchSize == 0) {
								batchSize = N;
							} else {
								batchSize = mBatchSize;
							}

							final long sortTime = DEBUG_LOADERS ? SystemClock
									.uptimeMillis() : 0;
							Collections.sort(apps,
									new ResolveInfo.DisplayNameComparator(
											packageManager));
							if (DEBUG_LOADERS) {
								Log.d(TAG,
										"sort took "
												+ (SystemClock.uptimeMillis() - sortTime)
												+ "ms");
							}
						}

						final long t2 = DEBUG_LOADERS ? SystemClock
								.uptimeMillis() : 0;

						startIndex = i;
						for (int j = 0; i < N && j < batchSize; j++) {
							// This builds the icon bitmaps.
							mAllAppsList.add(new ApplicationInfo(apps.get(i),
									mIconCache));
							i++;
						}

						final boolean first = i <= batchSize;
						final Callbacks callbacks = tryGetCallbacks(oldCallbacks);
						final ArrayList<ApplicationInfo> added = mAllAppsList.added;
						mAllAppsList.added = new ArrayList<ApplicationInfo>();

						mHandler.post(new Runnable() {
							@Override
							public void run() {
								final long t = SystemClock.uptimeMillis();
								if (callbacks != null) {
									if (first) {
										mBeforeFirstLoad = false;
										callbacks.bindAllApplications(added);
									} else {
										callbacks.bindAppsAdded(added);
									}
									if (DEBUG_LOADERS) {
										Log.d(TAG,
												"bound "
														+ added.size()
														+ " apps in "
														+ (SystemClock
																.uptimeMillis() - t)
														+ "ms");
									}
								} else {
									Log.d(TAG,
											"not binding apps: no Launcher activity");
								}
							}
						});

						if (DEBUG_LOADERS) {
							Log.d(TAG,
									"batch of " + (i - startIndex)
											+ " icons processed in "
											+ (SystemClock.uptimeMillis() - t2)
											+ "ms");
						}
					}

					if (mAllAppsLoadDelay > 0 && i < N) {
						try {
							if (DEBUG_LOADERS) {
								Log.d(TAG, "sleeping for " + mAllAppsLoadDelay
										+ "ms");
							}
							Thread.sleep(mAllAppsLoadDelay);
						} catch (InterruptedException exc) {
						}
					}
				}

				if (DEBUG_LOADERS) {
					Log.d(TAG,
							"cached all "
									+ N
									+ " apps in "
									+ (SystemClock.uptimeMillis() - t)
									+ "ms"
									+ (mAllAppsLoadDelay > 0 ? " (including delay)"
											: ""));
				}
			}

			private void updateSlideData(Context context) {
				final Callbacks oldCallbacks = mCallbacks.get();
				if (oldCallbacks == null) {
					// This launcher has exited and nobody bothered to tell us.
					// Just bail.
					Log.w(TAG,
							"LoaderThread running with no launcher (loadAllAppsByBatch)");
					return;
				}

				final ContentResolver cr = context.getContentResolver();
				int count = removePackageFromSlideData(context, cr);
				int addCount = addPackageToSlideData(context, cr, count);
				Log.e(TAG, "updateSlideData, count = " + count + ", addCount="
						+ addCount);

				final Callbacks callbacks = tryGetCallbacks(oldCallbacks);
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						final long t = SystemClock.uptimeMillis();
						if (callbacks != null) {
							callbacks.updateAllApps();
							if (DEBUG_LOADERS) {
								Log.d(TAG, "updateSlideData, apps in "
										+ (SystemClock.uptimeMillis() - t)
										+ "ms");
							}
						} else {
							Log.d(TAG, "not binding apps: no Launcher activity");
						}
					}
				});
			}

			int addPackageToSlideData(Context context, ContentResolver cr,
					int count) {
				final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
				int lastPos = 0;
				int add = 0;
				mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

				final PackageManager packageManager = context
						.getPackageManager();
				List<ResolveInfo> apps = null;
				apps = packageManager.queryIntentActivities(mainIntent, 0);

				if (apps.size() == 0) {
					return 0;
				}

				if (apps.size() > count) {
					final Uri insetUri = Applications.CONTENT_URI_NO_NOTIFICATION;
					Cursor c = cr.query(insetUri,
							new String[] { BaseColumns._ID },
							Applications.CONTAINER + "=?",
							new String[] { String
									.valueOf(Applications.CONTAINER_APPS) },
							null);

					if (c != null) {
						lastPos = c.getCount();
						c.close();
					}

					final String selfPkgName = mContext.getPackageName();
					for (int i = 0; i < apps.size(); i++) {
						ResolveInfo info = apps.get(i);
						// Do not add custom theme package to all application
						if (info.activityInfo.packageName
								.startsWith(ThemeUtils.THEME_PACKAGE_TOKEN)
								|| info.activityInfo.packageName
										.equals(selfPkgName)) {
							continue;
						}

						c = cr.query(
								Applications.CONTENT_URI_NO_NOTIFICATION,
								new String[] { Applications.PACKAGENAME },
								Applications.PACKAGENAME + "=?",
								new String[] { info.activityInfo.applicationInfo.packageName },
								null);

						if (c != null && !c.moveToNext()) {
							ContentValues values = new ContentValues();
							final android.content.pm.ApplicationInfo appInfo = info.activityInfo.applicationInfo;
							String intentInfo = appInfo.packageName + "|"
									+ info.activityInfo.name;

							values.put(BaseLauncherColumns.TITLE, info
									.loadLabel(packageManager).toString());
							values.put(BaseLauncherColumns.INTENT, intentInfo);
							values.put(Applications.CONTAINER,
									Applications.CONTAINER_APPS);
							values.put(Applications.POSITION, lastPos);
							values.put(BaseLauncherColumns.ITEM_TYPE,
									Applications.APPS_TYPE_APP);
							values.put(Applications.SYSAPP, 0);
							values.put(
									Applications.PACKAGENAME,
									info.activityInfo.applicationInfo.packageName);

							cr.insert(insetUri, values);
							Log.w(TAG,
									"addPackageToSlideData app="
											+ info.activityInfo.applicationInfo.packageName
											+ ", position=" + (lastPos));
							lastPos++;
							add++;
						}
						c.close();
					}
				}
				return add;
			}

			int removePackageFromSlideData(Context context, ContentResolver cr) {
				Cursor c = cr
						.query(Applications.CONTENT_URI_NO_NOTIFICATION,
								new String[] { BaseColumns._ID,
										BaseLauncherColumns.ITEM_TYPE,
										Applications.PACKAGENAME,
										Applications.POSITION },
								BaseLauncherColumns.ITEM_TYPE + " in " + "("
										+ Applications.APPS_TYPE_APP + ","
										+ Applications.APPS_TYPE_FOLDERAPP
										+ ")", null, null);
				int count = c.getCount();

				PackageManager packageManager = context.getPackageManager();
				ArrayList<ApplicationInfoEx> mRemoveAppsList = new ArrayList<ApplicationInfoEx>();
				if (c != null) {
					while (c.moveToNext()) {
						String packageName = c.getString(c
								.getColumnIndex(Applications.PACKAGENAME));
						if (!findPackage(packageName, packageManager)) {
							ApplicationInfoEx applicationInfoEx = new ApplicationInfoEx();
							applicationInfoEx.id = c.getInt(c
									.getColumnIndex(BaseColumns._ID));
							applicationInfoEx.itemType = c
									.getInt(c
											.getColumnIndex(BaseLauncherColumns.ITEM_TYPE));
							applicationInfoEx.packageName = packageName;
							applicationInfoEx.position = c.getInt(c
									.getColumnIndex(Applications.POSITION));
							mRemoveAppsList.add(applicationInfoEx);
							// Log.w(TAG, "removePackageFromSlideData app=" +
							// packageName+", position="+(applicationInfoEx.position));
						}
					}
					c.close();
				}

				count -= mRemoveAppsList.size();

				for (int i = 0; i < mRemoveAppsList.size(); i++) {
					ApplicationInfoEx slideInfo = mRemoveAppsList.get(i);

					c = cr.query(Applications.CONTENT_URI_NO_NOTIFICATION,
							new String[] { BaseColumns._ID,
									Applications.PACKAGENAME,
									Applications.POSITION },
							Applications.PACKAGENAME + "=?",
							new String[] { slideInfo.packageName }, null);
					if (c != null && c.moveToNext()) {
						slideInfo.position = c.getInt(c
								.getColumnIndex(Applications.POSITION));
						c.close();
					}

					final Uri updateUri = Applications
							.getCustomUri("/insertfolder");
					if (slideInfo.itemType == Applications.APPS_TYPE_APP) {
						cr.update(updateUri, null, null, new String[] { String
								.valueOf(slideInfo.position) });
					}

					Log.w(TAG, "removePackageFromSlideData app="
							+ slideInfo.packageName + ", position="
							+ (slideInfo.position));
					final Uri deleteUri = Applications.getContentUri(
							slideInfo.id, true);
					cr.delete(deleteUri, null, null);
				}

				return count;
			}

			boolean findPackage(String packageName,
					PackageManager packageManager) {
				if (packageName == null || "".equals(packageName)) {
					return false;
				}
				try {
					packageManager.getPackageInfo(packageName,
							PackageManager.PERMISSION_GRANTED);
					return true;
				} catch (PackageManager.NameNotFoundException e) {
					e.printStackTrace();
					return false;
				}
			}

			public void dumpState() {
				Log.d(TAG, "mLoader.mLoaderThread.mContext=" + mContext);
				Log.d(TAG, "mLoader.mLoaderThread.mWaitThread=" + mWaitThread);
				Log.d(TAG, "mLoader.mLoaderThread.mIsLaunching=" + mIsLaunching);
				Log.d(TAG, "mLoader.mLoaderThread.mStopped=" + mStopped);
				Log.d(TAG, "mLoader.mLoaderThread.mLoadAndBindStepFinished="
						+ mLoadAndBindStepFinished);
			}
		}

		public void dumpState() {
			Log.d(TAG, "mLoader.mItems size=" + mLoader.mItems.size());
			if (mLoaderThread != null) {
				mLoaderThread.dumpState();
			} else {
				Log.d(TAG, "mLoader.mLoaderThread=null");
			}
		}
	}

	/**
	 * This is called from the code that adds shortcuts from the intent
	 * receiver. This doesn't have a Cursor, but
	 */
	public ShortcutInfo getShortcutInfo(PackageManager manager, Intent intent,
			Context context) {
		return getShortcutInfo(manager, intent, context, null, -1, -1);
	}

	/**
	 * Make an ShortcutInfo object for a shortcut that is an application.
	 * 
	 * If c is not null, then it will be used to fill in missing data like the
	 * title and icon.
	 */
	public ShortcutInfo getShortcutInfo(PackageManager manager, Intent intent,
			Context context, Cursor c, int iconIndex, int titleIndex) {
		Bitmap icon = null;
		final ShortcutInfo info = new ShortcutInfo();

		ComponentName componentName = intent.getComponent();
		if (componentName == null) {
			return null;
		}

		// TODO: See if the PackageManager knows about this case. If it doesn't
		// then return null & delete this.

		// the resource -- This may implicitly give us back the fallback icon,
		// but don't worry about that. All we're doing with usingFallbackIcon is
		// to avoid saving lots of copies of that in the database, and most apps
		// have icons anyway.
		final ResolveInfo resolveInfo = manager.resolveActivity(intent, 0);
		if (resolveInfo != null) {
			icon = mIconCache.getIcon(componentName, resolveInfo);
		} else {
			// del by liumin, for doov lose wrorkspace icon when in low memory
			Log.w(TAG, "getShortcutInfo error, resolveInfo null! intent="
					+ intent);
			// try {
			// manager.getPackageInfo(componentName.getPackageName(), 0);
			// } catch (PackageManager.NameNotFoundException e) {
			// // As the package not found, do not create it's shortcut
			// return null;
			// }
		}
		// the db
		if (icon == null) {
			if (c != null) {
				icon = getIconFromCursor(c, iconIndex);
			}
		}
		// the fallback icon
		if (icon == null) {
			icon = getFallbackIcon();
			info.usingFallbackIcon = true;
			if (DEBUG_LOADERS)
				Log.d(TAG, "getShortcutInfo, getFallbackIcon ");
		}
		info.setIcon(icon);

		// from the resource
		if (resolveInfo != null) {
			info.title = resolveInfo.activityInfo.loadLabel(manager);
		}
		// from the db
		if (info.title == null) {
			if (c != null) {
				info.title = c.getString(titleIndex);
			}
		}
		// fall back to the class name of the activity
		if (info.title == null) {
			info.title = componentName.getClassName();
		}
		info.itemType = BaseLauncherColumns.ITEM_TYPE_APPLICATION;
		return info;
	}

	/**
	 * Make an ShortcutInfo object for a shortcut that isn't an application.
	 */
	private ShortcutInfo getShortcutInfo(Cursor c, Context context,
			int iconTypeIndex, int iconPackageIndex, int iconResourceIndex,
			int iconIndex, int titleIndex) {

		Bitmap icon = null;
		final ShortcutInfo info = new ShortcutInfo();
		info.itemType = BaseLauncherColumns.ITEM_TYPE_SHORTCUT;

		// TODO: If there's an explicit component and we can't install that,
		// delete it.

		info.title = c.getString(titleIndex);

		int iconType = c.getInt(iconTypeIndex);
		switch (iconType) {
		case BaseLauncherColumns.ICON_TYPE_RESOURCE:
			String packageName = c.getString(iconPackageIndex);
			String resourceName = c.getString(iconResourceIndex);
			PackageManager packageManager = context.getPackageManager();
			info.customIcon = false;
			// the resource
			try {
				Resources resources = packageManager
						.getResourcesForApplication(packageName);
				if (resources != null) {
					final int id = resources.getIdentifier(resourceName, null,
							null);
					icon = Utilities.createIconBitmap(
							resources.getDrawable(id), context);
				}
			} catch (Exception e) {
				// drop this. we have other places to look for icons
			}
			// the db
			if (icon == null) {
				icon = getIconFromCursor(c, iconIndex);
			}
			// the fallback icon
			if (icon == null) {
				icon = getFallbackIcon();
				info.usingFallbackIcon = true;
			}
			break;
		case BaseLauncherColumns.ICON_TYPE_BITMAP:
			icon = getIconFromCursor(c, iconIndex);
			if (icon == null) {
				icon = getFallbackIcon();
				info.customIcon = false;
				info.usingFallbackIcon = true;
			} else {
				info.customIcon = true;
			}
			break;
		default:
			icon = getFallbackIcon();
			info.usingFallbackIcon = true;
			info.customIcon = false;
			break;
		}
		info.setIcon(icon);
		return info;
	}

	Bitmap getIconFromCursor(Cursor c, int iconIndex) {
		if (DEBUG_LOADERS) {
			Log.d(TAG,
					"getIconFromCursor app="
							+ c.getString(c
									.getColumnIndexOrThrow(BaseLauncherColumns.TITLE)));
		}
		byte[] data = c.getBlob(iconIndex);
		try {
			return BitmapFactory.decodeByteArray(data, 0, data.length);
		} catch (Exception e) {
			return null;
		}
	}

//	//check if shortcut is existed
//	public boolean isDuplicate(Context context, String title, Intent intent) {
//		return isDuplicate(context, title, intent, false);
//	}
//	
//	public boolean isDuplicate(Context context, String title, Intent intent, boolean strict) {
//		if (strict) {
//			return isDuplicateByTitleAndIntent(context, title, intent);
//		} else {		
//			String pkgName = null;
//			
//			try {
//				pkgName = intent.getComponent().getPackageName();
//			} catch (Exception e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}	
//			
//			if (pkgName == null) {
//				return isDuplicateByTitle(context, title);
//			} else {
//				return isDuplicateByTitleAndPkgName(context, title, pkgName);
//			}
//		
//		}
//	}
//	
//	private boolean isDuplicateByTitle(Context context, String title){
//		//local variable
//		final ContentResolver cr = context.getContentResolver();
//		Cursor c = null;
//		boolean result = false;
//		
//		//body
//		c = cr.query(Favorites.CONTENT_URI, new String[] { "title" },
//				"title=?", new String[] { title },	null);
//		
//		try {
//			result = c.moveToFirst();
////			if (c != null && c.getCount() > 0) {
////				result = true;
////			}
//		} finally {
//			c.close();
//		}
//
//		return result;
//	}
//	
//	/**
//	 * @param context
//	 * @param title
//	 * @param data
//	 * @return
//	 */
//	private boolean isDuplicateByTitleAndPkgName(Context context,
//			String title, String pkgName) {
//		
//		//local variable
//		final ContentResolver cr = context.getContentResolver();
//		String intentUri = null;
//		Cursor c = null;
//		boolean result = false;
//		
//		//body
//		intentUri = "%" + pkgName + "%";
//		
//		c = cr.query(Favorites.CONTENT_URI, new String[] { "title", "intent" },
//				"title=? and intent like ?", new String[] { title, intentUri },
//				null);
//	
//		try {
//			//result = c.moveToFirst();
//			if (c != null && c.getCount() > 0) {
//				result = true;
//			}
//		} finally {
//			c.close();
//		}
//
//		return result;
//	}
//
//
//
//
//	
//	/**
//	 * @param context
//	 * @param title
//	 * @param data
//	 * @return
//	 */
//	private boolean isDuplicateByTitleAndIntent(Context context,
//			String title, Intent data) {
//		final ContentResolver cr = context.getContentResolver();
//		String intentUri = new String("");
//		Cursor c = null;
//		boolean result = false;
//		Intent intent = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
////		String shortClassName = data.getComponent().getShortClassName();
////		String className = data.getComponent().getClassName();
////		String packageName = data.getComponent().getPackageName();
//		
//		if (intent == null) {
//			intentUri = data.toUri(0);
//		} else {
//			intentUri = intent.toUri(0);
//		}
//
//		if (intentUri.indexOf("component=") > 0) {
//			intentUri = "%"
//					+ intentUri.substring(intentUri.indexOf("component="));
//			intentUri = intentUri.substring(0, intentUri.indexOf(";")) + "%";
//		} else if (intentUri.indexOf("#") > 0) {
//			intentUri = intentUri.substring(0, intentUri.indexOf("#")) + "%";
//		} else {
//			// don't change it.
//		}
//
//		c = cr.query(Favorites.CONTENT_URI, new String[] { "title", "intent" },
//				"title=? and intent like ?", new String[] { title, intentUri },
//				null);
//
//		try {
//			//result = c.moveToFirst();
//			if (c != null && c.getCount() > 0) {
//				result = true;
//			}
//		} finally {
//			c.close();
//		}
//
//		return result;
//	}
	
	public boolean hasShortcut(Context context, ShortcutInfo info, Intent data) {
		boolean result = false;		
		
		if (info.itemType==Favorites.ITEM_TYPE_APPLICATION){
				
			final String title = info.title.toString();
			final String className = Launcher.getClassName(info, data);
			final String shortClassName = Launcher.getShortClassName(info, data);
			final String pkgName = Launcher.getPackageName(info, data);
			
			final String intentUri = "%" + className + "%";
			final String intentUri2 = "%" + pkgName + "/" + shortClassName + "%";
			
			if (hasShortcutInDB(context, title, intentUri) || hasShortcutInDB(context, title, intentUri2)) 
				return true;				
		}
		
		return result;
	}

	/**
	 * @param context
	 * @param title
	 * @param intentUri
	 * @return
	 */
	private boolean hasShortcutInDB(Context context, final String title,
			final String intentUri) {
		boolean result = false;		
		Cursor c = null;
		
		try {			
			final ContentResolver cr = context.getContentResolver();
			
			c = cr.query(Favorites.CONTENT_URI, new String[] { "title", "intent" },
					"title=? and intent like ?", new String[] { title, intentUri },
					null);
			
			if (c != null && c.getCount() > 0) {
				result = true;
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
			result = false;
		} finally {
			c.close();
		}
		
		return result;
	}
	
	ShortcutInfo addShortcut(Context context, Intent data,
			CellLayout.CellInfo cellInfo, boolean notify) {

		final ShortcutInfo info = infoFromShortcutIntent(context, data);

		if (hasShortcut(context, info, data)) 
			return null;

		addItemToDatabase(context, info, Favorites.CONTAINER_DESKTOP,
				cellInfo.screen, cellInfo.cellX, cellInfo.cellY, notify);

		return info;
	}

	// if the count change when update slider data, it will return false;

	ShortcutInfo addShortcutInDock(Context context, Intent data,
			ShortcutInfo dockItemInfo, boolean notify) {

		final ShortcutInfo info = infoFromShortcutIntent(context, data);

		if (hasShortcut(context, info, data)) 
			return null;

		addItemToDatabase(context, info, Favorites.CONTAINER_DOCKBAR,
				dockItemInfo.screen, dockItemInfo.cellX, dockItemInfo.cellY,
				notify);

		return info;
	}

	private ShortcutInfo infoFromShortcutIntent(Context context, Intent data) {
		Intent intent = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
		String name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);
		Parcelable bitmap = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON);

		Bitmap icon = null;
		// boolean filtered = false;
		boolean customIcon = false;
		ShortcutIconResource iconResource = null;

		if (bitmap != null && bitmap instanceof Bitmap) {
			icon = Utilities.createIconBitmap(new FastBitmapDrawable(
					(Bitmap) bitmap), context);
			// filtered = true;
			customIcon = true;
		} else {
			Parcelable extra = data
					.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE);
			if (extra != null && extra instanceof ShortcutIconResource) {
				try {
					iconResource = (ShortcutIconResource) extra;
					final PackageManager packageManager = context
							.getPackageManager();
					Resources resources = packageManager
							.getResourcesForApplication(iconResource.packageName);
					final int id = resources.getIdentifier(
							iconResource.resourceName, null, null);
					icon = Utilities.createIconBitmap(
							resources.getDrawable(id), context);
				} catch (Exception e) {
					Log.w(TAG, "Could not load shortcut icon: " + extra);
				}
			}
		}

		final ShortcutInfo info = new ShortcutInfo();

		if (icon == null) {
			icon = getFallbackIcon();
			info.usingFallbackIcon = true;
		}

		info.setIcon(Utilities.createCompoundBitmapEx(name, icon));

		info.title = name;
		info.intent = intent;
		info.customIcon = customIcon;
		info.iconResource = iconResource;

		return info;
	}

	private static void loadLiveFolderIcon(Context context, Cursor c,
			int iconTypeIndex, int iconPackageIndex, int iconResourceIndex,
			LiveFolderInfo liveFolderInfo) {

		int iconType = c.getInt(iconTypeIndex);
		switch (iconType) {
		case BaseLauncherColumns.ICON_TYPE_RESOURCE:
			String packageName = c.getString(iconPackageIndex);
			String resourceName = c.getString(iconResourceIndex);
			PackageManager packageManager = context.getPackageManager();
			try {
				Resources resources = packageManager
						.getResourcesForApplication(packageName);
				final int id = resources
						.getIdentifier(resourceName, null, null);
				liveFolderInfo.icon = Utilities.createIconBitmap(
						resources.getDrawable(id), context);
			} catch (Exception e) {
				liveFolderInfo.icon = Utilities.createIconBitmap(
						context.getResources().getDrawable(
								R.drawable.ic_launcher_folder), context);
			}
			liveFolderInfo.iconResource = new Intent.ShortcutIconResource();
			liveFolderInfo.iconResource.packageName = packageName;
			liveFolderInfo.iconResource.resourceName = resourceName;
			break;
		default:
			liveFolderInfo.icon = Utilities.createIconBitmap(context
					.getResources().getDrawable(R.drawable.ic_launcher_folder),
					context);
		}
	}

	void updateSavedIcon(Context context, ShortcutInfo info, Cursor c,
			int iconIndex) {
		// If this icon doesn't have a custom icon, check to see
		// what's stored in the DB, and if it doesn't match what
		// we're going to show, store what we are going to show back
		// into the DB. We do this so when we're loading, if the
		// package manager can't find an icon (for example because
		// the app is on SD) then we can use that instead.
		if (info.onExternalStorage && !info.customIcon
				&& !info.usingFallbackIcon) {
			boolean needSave;
			byte[] data = c.getBlob(iconIndex);
			try {
				if (data != null) {
					Bitmap saved = BitmapFactory.decodeByteArray(data, 0,
							data.length);
					Bitmap loaded = info.getIcon(mIconCache);
					needSave = !saved.sameAs(loaded);
				} else {
					needSave = true;
				}
			} catch (Exception e) {
				needSave = true;
			}
			if (needSave) {
				Log.d(TAG, "going to save icon bitmap for info=" + info);
				// This is slower than is ideal, but this only happens either
				// after the froyo OTA or when the app is updated with a new
				// icon.
				updateItemInDatabase(context, info);
			}
		}
	}

	/**
	 * Return an existing UserFolderInfo object if we have encountered this ID
	 * previously, or make a new one.
	 */
	private static UserFolderInfo findOrMakeUserFolder(
			HashMap<Long, FolderInfo> folders, long id) {
		// See if a placeholder was created for us already
		FolderInfo folderInfo = folders.get(id);
		if (folderInfo == null || !(folderInfo instanceof UserFolderInfo)) {
			// No placeholder -- create a new instance
			folderInfo = new UserFolderInfo();
			folders.put(id, folderInfo);
		}
		return (UserFolderInfo) folderInfo;
	}

	/**
	 * Return an existing UserFolderInfo object if we have encountered this ID
	 * previously, or make a new one.
	 */
	private static LiveFolderInfo findOrMakeLiveFolder(
			HashMap<Long, FolderInfo> folders, long id) {
		// See if a placeholder was created for us already
		FolderInfo folderInfo = folders.get(id);
		if (folderInfo == null || !(folderInfo instanceof LiveFolderInfo)) {
			// No placeholder -- create a new instance
			folderInfo = new LiveFolderInfo();
			folders.put(id, folderInfo);
		}
		return (LiveFolderInfo) folderInfo;
	}

	@SuppressWarnings("unused")
	private static String getLabel(PackageManager manager,
			ActivityInfo activityInfo) {
		String label = activityInfo.loadLabel(manager).toString();
		if (label == null) {
			label = manager.getApplicationLabel(activityInfo.applicationInfo)
					.toString();
			if (label == null) {
				label = activityInfo.name;
			}
		}
		return label;
	}

	private static final Collator sCollator = Collator.getInstance();
	public static final Comparator<ApplicationInfo> APP_NAME_COMPARATOR = new Comparator<ApplicationInfo>() {
		@Override
		public final int compare(ApplicationInfo a, ApplicationInfo b) {
			return sCollator.compare(a.title.toString(), b.title.toString());
		}
	};

	public void dumpState() {
		Log.d(TAG, "mBeforeFirstLoad=" + mBeforeFirstLoad);
		Log.d(TAG, "mCallbacks=" + mCallbacks);
		ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList.data",
				mAllAppsList.data);
		ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList.added",
				mAllAppsList.added);
		ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList.removed",
				mAllAppsList.removed);
		ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList.modified",
				mAllAppsList.modified);
		mLoader.dumpState();
	}
	
	public String dumpState2String(String appName){
		String str = new String("");
		
		str+=ApplicationInfo.dumpApplicationInfoList2String(TAG, "All apps: ",
				mAllAppsList.data, appName);
//		str+=ApplicationInfo.dumpApplicationInfoList2String(TAG, "Added apps: ",
//				mAllAppsList.added);
//		str+=ApplicationInfo.dumpApplicationInfoList2String(TAG, "Removed apps: ",
//				mAllAppsList.removed);
//		str+=ApplicationInfo.dumpApplicationInfoList2String(TAG, "Modified apps: ",
//				mAllAppsList.modified);
		
		return str;		
	}
	
}