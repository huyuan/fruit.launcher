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


import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources.NotFoundException;
import android.content.res.TypedArray;
import android.content.res.Resources;
import android.graphics.Camera;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Canvas;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextMenu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.animation.Transformation;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetProviderInfo;

import java.util.ArrayList;

import com.fruit.launcher.LauncherSettings.BaseLauncherColumns;
import com.fruit.launcher.LauncherSettings.Favorites;
import com.fruit.launcher.setting.SettingUtils;

public class CellLayout extends ViewGroup {

	private static final String TAG = "CellLayout";
	private static final int INVALID_CELL = -1;

	private boolean mPortrait;
	private boolean mIsFullScreen;

	private int mCellWidth;
	private int mCellHeight;

	private int mLongAxisStartPadding;
	private int mLongAxisEndPadding;

	private int mShortAxisStartPadding;
	private int mShortAxisEndPadding;

	private int mShortAxisCells;
	private int mLongAxisCells;

	private int mWidthGap;
	private int mHeightGap;

	private int mBubbleCount = 0;
	private int mWidgetCount = 0;
	private int mFolderCount = 0;
	
	private final Rect mRect = new Rect();
	private final CellInfo mCellInfo = new CellInfo();

	int[] mCellXY = new int[2];
	boolean[][] mOccupied;

	private RectF mDragRect = new RectF();

	private boolean mDirtyTag;
	private boolean mLastDownOnOccupiedCell = false;

	private final WallpaperManager mWallpaperManager;
	private Camera mCamera;
	// used for anti-alias
	private PaintFlagsDrawFilter mCanvasFlag;

	private int pageIndex = -1;

	public CellLayout(Context context) {
		this(context, null);
	}

	public CellLayout(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public CellLayout(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		TypedArray a = context.obtainStyledAttributes(attrs,
				R.styleable.CellLayout, defStyle, 0);

		mCellWidth = a.getDimensionPixelSize(R.styleable.CellLayout_cellWidth,
				10);
		mCellHeight = a.getDimensionPixelSize(
				R.styleable.CellLayout_cellHeight, 10);

		mLongAxisStartPadding = Configurator.getDimensPixelSize(context,
				Configurator.CONFIG_CELL_STARTPADDING, 10);
		// a.getDimensionPixelSize(R.styleable.CellLayout_longAxisStartPadding,
		// 10);
		mLongAxisEndPadding = Configurator.getDimensPixelSize(context,
				Configurator.CONFIG_CELL_ENDPADDING, 10);
		// a.getDimensionPixelSize(R.styleable.CellLayout_longAxisEndPadding,
		// 10);
		mShortAxisStartPadding = a.getDimensionPixelSize(
				R.styleable.CellLayout_shortAxisStartPadding, 10);
		mShortAxisEndPadding = a.getDimensionPixelSize(
				R.styleable.CellLayout_shortAxisEndPadding, 10);

		// mShortAxisCells = a.getInt(R.styleable.CellLayout_shortAxisCells, 4);
		// mLongAxisCells = a.getInt(R.styleable.CellLayout_longAxisCells, 4);
		mShortAxisCells = Configurator.getIntegerConfig(context,
				Configurator.CONFIG_SHORTAXISCELLS, 4);
		mLongAxisCells = Configurator.getIntegerConfig(context,
				Configurator.CONFIG_LONGAXISCELLS, 4);
		a.recycle();

		setAlwaysDrawnWithCacheEnabled(false);

		if (mOccupied == null) {
			if (mPortrait) {
				mOccupied = new boolean[mShortAxisCells][mLongAxisCells];
			} else {
				mOccupied = new boolean[mLongAxisCells][mShortAxisCells];
			}
		}

		mWallpaperManager = WallpaperManager.getInstance(getContext());
		mCamera = new Camera();
	}


	int cellToIndex(int cellX, int cellY) {
		int result = INVALID_CELL;
		
		int[] checks = new int[ItemInfo.COL * ItemInfo.ROW];
		for (int i = 0; i < ItemInfo.COL * ItemInfo.ROW; i++) {
			checks[i] = -1;
		}

		//
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			final View v = getChildAt(i);
			if (v == null)
				continue;
			
			final LayoutParams lp = (LayoutParams) v.getLayoutParams();
			final ItemInfo itemInfo = (ItemInfo) v.getTag();
			
			if (itemInfo==null && lp==null)
				continue;
			
			if (lp != null && lp.isDragging)
				continue;
			
			try {	
				
				checksByItemInfoOrLP(checks, i, lp, itemInfo);
			
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			int overNum = cellY * (ItemInfo.COL) + cellX;

			if (checks[overNum] > -1) {
				result = i;
				break;
			} else {
				// pass
			}
		}

		checks=null;
		return result;
	}

	/**
	 * @param checks
	 * @param i
	 * @param lp
	 * @param itemInfo
	 */
	public void checksByItemInfoOrLP(int[] checks, int i,
			final LayoutParams lp, final ItemInfo itemInfo) {
		int num = -1;
		
		if((itemInfo.cellX >=0 && itemInfo.cellY >= 0) && (itemInfo.spanX >= 1 && itemInfo.spanY >= 1)){
			num =itemInfo.cellY * (ItemInfo.COL) + itemInfo.cellX;
			for (int j = 0; j < itemInfo.spanX; j++) {
				for (int k = 0; k < itemInfo.spanY; k++) {
					checks[num + ItemInfo.COL * k + j] = i;
				}
			}
			Log.d(TAG,"itemInfo="+itemInfo.toString());
		}		
		
		if ((num<0) && ((lp.cellX >= 0 && lp.cellY >= 0) && (lp.cellHSpan >= 1 && lp.cellVSpan >= 1))) {
			num = lp.cellY * (ItemInfo.COL) + lp.cellX;
			for (int j = 0; j < lp.cellHSpan; j++) {
				for (int k = 0; k < lp.cellVSpan; k++) {
					checks[num + ItemInfo.COL * k + j] = i;
				}
			}
			Log.d(TAG, "lp="+lp.toString());
		}
		

	}
	
	/**
	 * @param checks
	 * @param i
	 * @param lp
	 * @param itemInfo
	 */
	public void checksByItemInfoOrLPNot1x1(int[] checks, int i,
			final LayoutParams lp, final ItemInfo itemInfo) {
		int num = -1;
		
		if ((lp.cellX >= 0 && lp.cellY >= 0) && (lp.cellHSpan > 1 || lp.cellVSpan > 1)) {
			num = lp.cellY * (ItemInfo.COL) + lp.cellX;
			for (int j = 0; j < lp.cellHSpan; j++) {
				for (int k = 0; k < lp.cellVSpan; k++) {
					checks[num + ItemInfo.COL * k + j] = i;
				}
			}
			Log.d(TAG, "lp="+lp.toString());
		}
		
		if((num<0) && ((itemInfo.cellX >=0 && itemInfo.cellY >= 0) && (itemInfo.spanX > 1 || itemInfo.spanY > 1))){
			num =itemInfo.cellY * (ItemInfo.COL) + itemInfo.cellX;
			for (int j = 0; j < itemInfo.spanX; j++) {
				for (int k = 0; k < itemInfo.spanY; k++) {
					checks[num + ItemInfo.COL * k + j] = i;
				}
			}
			Log.d(TAG,"itemInfo="+itemInfo.toString());
		}
	}

	int cellToIndex(int[] cellXY) {
		if (cellXY == null)
			return INVALID_CELL;
		else
			return cellToIndex(cellXY[0], cellXY[1]);
	}

	int numberToIndex(int number) {
		int result = INVALID_CELL;

		int[] cellXY = mCellXY;
		if (numberToCell(number, cellXY)) {
			result = cellToIndex(cellXY);
		}

		return result;

	}

	boolean numberToCell(int number, int[] cellXY) {
		if (cellXY == null)
			return false;

		if (number < 0 || number >= getMaxCount()) {
			cellXY[0] = -1;
			cellXY[1] = -1;
			return false;
		} else {
			cellXY[0] = number % getCountX();
			cellXY[1] = number / getCountX();			
			return true;
		}
		
	}
	
	int cellToSeqNo(int cellX, int cellY) {
		return getPageIndex()*getCountX()*getCountY()+cellY * getCountX() + cellX;
	}

	int cellToNumber(int cellX, int cellY) {
		return cellY * getCountX() + cellX;
	}

	int cellToNumber(int[] cellXY) {
		if (cellXY == null)
			return INVALID_CELL;
		else
			return cellToNumber(cellXY[0], cellXY[1]);
	}


//	int getCellSpanX(int index) {
//		int result = INVALID_CELL;
//
//		final View v = getChildAt(index);
//		if (v == null)
//			return result;
//
//	    final ItemInfo itemInfo = (ItemInfo) v.getTag();
//	    final LayoutParams lp = (LayoutParams) v.getLayoutParams();
//	    
//		if (itemInfo==null && lp == null)
//			return result;
//		
//		if (itemInfo!=null && itemInfo.spanX>=1){
//			result = itemInfo.spanX;
//		}
//		
//		if ((result<0) && (lp!=null && lp.cellHSpan>=1)) {
//			result = lp.cellHSpan;
//		}
//
//		return result;
//	}
//
//	int getCellSpanY(int index) {
//		int result = INVALID_CELL;
//
//		final View v = getChildAt(index);
//		if (v == null)
//			return result;
//
//	    final ItemInfo itemInfo = (ItemInfo) v.getTag();
//	    final LayoutParams lp = (LayoutParams) v.getLayoutParams();
//	    
//		if (itemInfo==null && lp == null)
//			return result;
//		
//		if (itemInfo!=null && itemInfo.spanY>=1){
//			result = itemInfo.spanY;
//		}
//		
//		if ((result<0) && (lp!=null && lp.cellVSpan>=1)) {
//			result = lp.cellVSpan;
//		}
//
//		return result;
//	}

	boolean isFull() {
		boolean result = false;
		int i=0;
		
		if (getChildCount() >= getMaxCount()) {
			result = true;
		} else {
			for (i=0; i<getMaxCount();i++){
				int index = numberToIndex(i);
				if (index<0){
					result=false;
					break;
				}
			}
			if(i>=getMaxCount())
				result=true;
		}

		return result;
	}

//	boolean isOverFull() {
//		boolean result = false;
//
//		if (getChildCount() > getMaxCount()) {
//			result = true;
//		}
//
//		return result;
//	}


	boolean hasNumber(int[] temp, int number) {
		boolean result = false;

		if (temp == null)
			return result;

		for (int i = 0; i < temp.length; i++) {
			if (temp[i] == number) {
				result = true;
				break;
			}
		}

		return result;
	}

//	void fixOverlappingCell() {
//		int cellX = -1, cellY = -1;
//
//		int[] temp = new int[getMaxCount()];
//		int j = 0;
//
//		for (int i = 0; i < getChildCount(); i++) {
//			View v = getChildAt(i);
//			if (v == null)
//				continue; // go on
//
//			LayoutParams lp = (LayoutParams) v.getLayoutParams();
//			if (lp == null)
//				continue; // go on
//
//			int number = cellToNumber(lp.cellX, lp.cellY);
//
//			if (hasNumber(temp, number)) {
//
//			} else {
//				temp[j] = number;
//			}
//
//		}
//	}

	@Override
	public void dispatchDraw(Canvas canvas) {
		if (SettingUtils.mHighQuality
				&& (SettingUtils.mTransitionEffect > Effects.EFFECT_TYPE_CLASSIC && SettingUtils.mTransitionEffect < Effects.EFFECT_MAX)) {
			// EFFECT_TYPE_CLASSIC has no need to draw with high quality
			if (mCanvasFlag == null) {
				mCanvasFlag = new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG
						| Paint.FILTER_BITMAP_FLAG);
			}
			canvas.setDrawFilter(mCanvasFlag);
		} else {
			canvas.setDrawFilter(null);
		}
		super.dispatchDraw(canvas);
	}

	@Override
	public void cancelLongPress() {
		super.cancelLongPress();

		// Cancel long press for all children
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			final View child = getChildAt(i);
			child.cancelLongPress();
		}
	}
	
	public int getBubbleCount(){
		return mBubbleCount;
	}
	
	public int getWidgetCount(){
		return mWidgetCount;
	}
	
	public int getFolderCount(){
		return mFolderCount;
	}
	
	public void setAllCount() {
		mBubbleCount=0;
		mWidgetCount=0;
		mFolderCount=0;
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			final View child = getChildAt(i);
			Log.d(TAG, child.toString()+",tag:"+child.getTag().toString());
			if (child instanceof FolderIcon) {
				mFolderCount++;
				//Object info = child.getTag();
				//Object folderInfo = /*(UserFolderInfo) */((FolderIcon)child).getTag();
				UserFolderInfo userFolderInfo = (UserFolderInfo)(((FolderIcon)child).getTag());
				//Log.d(TAG,"FolderIcon:info="+info.toString()+",folderinfo="+folderInfo.toString());
				//UserFolder folder = (UserFolder) (((FolderIcon)child).getParent().);
				final int size = userFolderInfo.getSize();
				for (int j=0; j<size; j++){
					ShortcutInfo shortcutInfo = userFolderInfo.contents.get(j);
					if (shortcutInfo.itemType == BaseLauncherColumns.ITEM_TYPE_APPLICATION){
						mBubbleCount++;
					} else {
						mWidgetCount++;
					}
				}
			} else {
				if (child instanceof CustomAppWidget || child instanceof LauncherAppWidgetHostView){				
					mWidgetCount++;
				} else if (child instanceof BubbleTextView) {					
//					Object info = child.getTag();
//					Object cellInfo = ((BubbleTextView) child).getTag();
					ShortcutInfo shortcutInfo = (ShortcutInfo) (((BubbleTextView) child).getTag());
//					Log.d(TAG,"BubbleTextView,info="+info.toString()
//							+",cellInfo="+cellInfo.toString()
//							+",shortcut="+shortcutInfo.toString());
					
					if (shortcutInfo.itemType == BaseLauncherColumns.ITEM_TYPE_APPLICATION){
						mBubbleCount++;
					} else {
						mWidgetCount++;
					}
					
					
				}
			}
		}
	}
	


	int getCountX() {
		return mPortrait ? mShortAxisCells : mLongAxisCells;
	}

	int getCountY() {
		return mPortrait ? mLongAxisCells : mShortAxisCells;
	}

	int getMaxCount() {
		return getCountX() * getCountY();
	}

	@Override
	public void addView(View child, int index, ViewGroup.LayoutParams params) {
		// Generate an id for each view, this assumes we have at most 256x256
		// cells
		// per workspace screen
		final LayoutParams cellParams = (LayoutParams) params;
		cellParams.regenerateId = true;

		super.addView(child, index, params);
	}

	@Override
	public void requestChildFocus(View child, View focused) {
		super.requestChildFocus(child, focused);
		if (child != null) {
			Rect r = new Rect();
			child.getDrawingRect(r);
			requestRectangleOnScreen(r);
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		mCellInfo.screen = ((ViewGroup) getParent()).indexOfChild(this);
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		final int action = ev.getAction();
		final CellInfo cellInfo = mCellInfo;

		if (action == MotionEvent.ACTION_DOWN) {
			final Rect frame = mRect;
			final int x = (int) ev.getX() + mScrollX;
			final int y = (int) ev.getY() + mScrollY;
			final int count = getChildCount();

			boolean found = false;
			for (int i = count - 1; i >= 0; i--) {
				final View child = getChildAt(i);

				if ((child.getVisibility()) == VISIBLE
						|| child.getAnimation() != null) {
					child.getHitRect(frame);
					if (frame.contains(x, y)) {
						final LayoutParams lp = (LayoutParams) child
								.getLayoutParams();
						cellInfo.cell = child;
						cellInfo.cellX = lp.cellX;
						cellInfo.cellY = lp.cellY;
						cellInfo.spanX = lp.cellHSpan;
						cellInfo.spanY = lp.cellVSpan;
						cellInfo.valid = true;
						found = true;
						mDirtyTag = false;
						break;
					}
				}
			}

			mLastDownOnOccupiedCell = found;

			if (!found) {
				int cellXY[] = mCellXY;
				pointToCellExact(x, y, cellXY);

				final boolean portrait = mPortrait;
				final int xCount = portrait ? mShortAxisCells : mLongAxisCells;
				final int yCount = portrait ? mLongAxisCells : mShortAxisCells;

				final boolean[][] occupied = mOccupied;
				findOccupiedCells(xCount, yCount, occupied, null);

				cellInfo.cell = null;
				cellInfo.cellX = cellXY[0];
				cellInfo.cellY = cellXY[1];
				cellInfo.spanX = 1;
				cellInfo.spanY = 1;
				cellInfo.valid = cellXY[0] >= 0 && cellXY[1] >= 0
						&& cellXY[0] < xCount && cellXY[1] < yCount
						&& !occupied[cellXY[0]][cellXY[1]];

				cellInfo.screen = ((ViewGroup) getParent()).indexOfChild(this);
				// Instead of finding the interesting vacant cells here, wait
				// until a
				// caller invokes getTag() to retrieve the result. Finding the
				// vacant
				// cells is a bit expensive and can generate many new objects,
				// it's
				// therefore better to defer it until we know we actually need
				// it.

				mDirtyTag = true;
			}
			setTag(cellInfo);
		} else if (action == MotionEvent.ACTION_UP) {
			cellInfo.cell = null;
			cellInfo.cellX = -1;
			cellInfo.cellY = -1;
			cellInfo.spanX = 0;
			cellInfo.spanY = 0;
			cellInfo.valid = false;
			cellInfo.screen = ((ViewGroup) getParent()).indexOfChild(this);
			mDirtyTag = false;
			setTag(cellInfo);
		}

		return false;
	}

	@Override
	public CellInfo getTag() {
		final CellInfo info = (CellInfo) super.getTag();
		if (mDirtyTag && info.valid) {
			final boolean portrait = mPortrait;
			final int xCount = portrait ? mShortAxisCells : mLongAxisCells;
			final int yCount = portrait ? mLongAxisCells : mShortAxisCells;

			final boolean[][] occupied = mOccupied;
			findOccupiedCells(xCount, yCount, occupied, null);

			findIntersectingVacantCells(info, info.cellX, info.cellY, xCount,
					yCount, occupied);

			mDirtyTag = false;
		}
		return info;
	}

	private static void findIntersectingVacantCells(CellInfo cellInfo, int x,
			int y, int xCount, int yCount, boolean[][] occupied) {

		cellInfo.maxVacantSpanX = Integer.MIN_VALUE;
		cellInfo.maxVacantSpanXSpanY = Integer.MIN_VALUE;
		cellInfo.maxVacantSpanY = Integer.MIN_VALUE;
		cellInfo.maxVacantSpanYSpanX = Integer.MIN_VALUE;
		cellInfo.clearVacantCells();

		if (occupied[x][y]) {
			return;
		}

		cellInfo.current.set(x, y, x, y);

		findVacantCell(cellInfo.current, xCount, yCount, occupied, cellInfo);
	}

	private static void findVacantCell(Rect current, int xCount, int yCount,
			boolean[][] occupied, CellInfo cellInfo) {

		addVacantCell(current, cellInfo);

		if (current.left > 0) {
			if (isColumnEmpty(current.left - 1, current.top, current.bottom,
					occupied)) {
				current.left--;
				findVacantCell(current, xCount, yCount, occupied, cellInfo);
				current.left++;
			}
		}

		if (current.right < xCount - 1) {
			if (isColumnEmpty(current.right + 1, current.top, current.bottom,
					occupied)) {
				current.right++;
				findVacantCell(current, xCount, yCount, occupied, cellInfo);
				current.right--;
			}
		}

		if (current.top > 0) {
			if (isRowEmpty(current.top - 1, current.left, current.right,
					occupied)) {
				current.top--;
				findVacantCell(current, xCount, yCount, occupied, cellInfo);
				current.top++;
			}
		}

		if (current.bottom < yCount - 1) {
			if (isRowEmpty(current.bottom + 1, current.left, current.right,
					occupied)) {
				current.bottom++;
				findVacantCell(current, xCount, yCount, occupied, cellInfo);
				current.bottom--;
			}
		}
	}

	private static void addVacantCell(Rect current, CellInfo cellInfo) {
		CellInfo.VacantCell cell = CellInfo.VacantCell.acquire();
		cell.cellX = current.left;
		cell.cellY = current.top;
		cell.spanX = current.right - current.left + 1;
		cell.spanY = current.bottom - current.top + 1;
		if (cell.spanX > cellInfo.maxVacantSpanX) {
			cellInfo.maxVacantSpanX = cell.spanX;
			cellInfo.maxVacantSpanXSpanY = cell.spanY;
		}
		if (cell.spanY > cellInfo.maxVacantSpanY) {
			cellInfo.maxVacantSpanY = cell.spanY;
			cellInfo.maxVacantSpanYSpanX = cell.spanX;
		}
		cellInfo.vacantCells.add(cell);
	}

	private static boolean isColumnEmpty(int x, int top, int bottom,
			boolean[][] occupied) {
		for (int y = top; y <= bottom; y++) {
			if (occupied[x][y]) {
				return false;
			}
		}
		return true;
	}

	private static boolean isRowEmpty(int y, int left, int right,
			boolean[][] occupied) {
		for (int x = left; x <= right; x++) {
			if (occupied[x][y]) {
				return false;
			}
		}
		return true;
	}

//	int findFirstVacantCell() {
//		int number = INVALID_CELL;
//
//		for (int i = 0; i < getMaxCount(); i++) {
//			View v = getChildAt(numberToIndex(i));
//			if (v == null) {
//				number = i;
//				break;
//			}
//		}
//
//		return number;
//	}
//
//	int findLastVacantCell() {
//		int number = INVALID_CELL;
//
//		for (int i = getMaxCount() - 1; i >= 0; i--) {
//			View v = getChildAt(numberToIndex(i));
//			if (v == null) {
//				number = i;
//				break;
//			}
//		}
//
//		return number;
//	}
	
	//void printChecks(int[] checks){
		//for (int i = 0; i < getMaxCount(); i++) {
		//	Log.d(TAG, "checks["+i+"]="+checks[i]);
		//}
	//}
	
	int findFirstVacantCell() {
		int number = INVALID_CELL;

		int[] checks = new int[ItemInfo.COL*ItemInfo.ROW];
		for (int i = 0; i < getMaxCount(); i++) {
			checks[i]=-1;
		}
		
		try {
			final int count = getChildCount();
			
			for (int i=0;i<count;i++){
				final View v = getChildAt(i);
				if (v==null)
					continue;
				
				final LayoutParams lp = (LayoutParams) v.getLayoutParams();
				final ItemInfo itemInfo = (ItemInfo) v.getTag();
				
				if (itemInfo==null && lp==null)
					continue;
				
				checksByItemInfoOrLP(checks, i, lp, itemInfo);	
			}
			
			//printChecks(checks);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		for (int i = 0; i < getMaxCount(); i++) {
			if(checks[i]<0){
				number = i;
				break;
			}
		}
		
		checks=null;
		return number;
	}

	int findLastVacantCell() {
		int number = INVALID_CELL;

		int[] checks = new int[ItemInfo.COL*ItemInfo.ROW];
		for (int i = 0; i < getMaxCount(); i++) {
			checks[i]=-1;
		}
		
		try {
			final int count = getChildCount();
			
			for (int i=0;i<count;i++){
				final View v = getChildAt(i);
				if (v==null)
					continue;
				
				final LayoutParams lp = (LayoutParams) v.getLayoutParams();
				final ItemInfo itemInfo = (ItemInfo) v.getTag();
				
				if (itemInfo==null && lp==null)
					continue;
				
				checksByItemInfoOrLP(checks, i, lp, itemInfo);

			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		for (int i = getMaxCount()-1; i >=0 ; i--) {
			if(checks[i]<0){
				number = i;
				break;
			}
		}

		checks=null;
		return number;
	}

//	View getLastChild1x1() {		
//		View child = null;
//
//		for (int i = getMaxCount() - 1; i >= 0; i--) {
//			View v = getChildAt(numberToIndex(i));
//			if (v == null)
//				continue;
//
//			LayoutParams lp = (LayoutParams) v.getLayoutParams();
//			if (lp == null)
//				continue;
//
//			if (lp.cellHSpan == 1 && lp.cellVSpan == 1) {
//				child = v;
//				break;
//			}
//		}
//
//		return child;
//
//	}

	public void changeCellXY(View v, int cellX, int cellY, int x, int y) {
		if (v == null)
			return;

	    final ItemInfo itemInfo = (ItemInfo) v.getTag();
		if (itemInfo==null)
			return;
		
		itemInfo.cellX = cellX;
		itemInfo.cellY = cellY;
		
		Log.d(TAG,"itemInfo="+itemInfo.toString());		
	
//		if (v instanceof LiveFolderIcon) {
//
//		} else if (v instanceof FolderIcon) {
//			UserFolderInfo userFolderInfo = (UserFolderInfo)(((FolderIcon)v).getTag());
//			userFolderInfo.cellX = cellX;
//			userFolderInfo.cellY = cellY;
//		} else if (v instanceof CustomAppWidget) {
//			CustomAppWidget customAppWidget = (CustomAppWidget) v;
//			CustomAppWidgetInfo winfo = (CustomAppWidgetInfo) customAppWidget.getTag();
//			winfo.cellX = cellX;
//			winfo.cellY = cellY;
//		} else if (v instanceof BubbleTextView) {
//			ShortcutInfo shortcutInfo = (ShortcutInfo) (((BubbleTextView) v).getTag());
//			shortcutInfo.cellX = cellX;
//			shortcutInfo.cellY = cellY;
//		} else if (v instanceof LauncherAppWidgetHostView) {
////			LauncherAppWidgetHostView view = (LauncherAppWidgetHostView) v;
////			AppWidgetProviderInfo appWidgetInfo = view.getAppWidgetInfo();			
//		} else {
//			Log.e(TAG, "Unknown item type when add in screen");
//		}
		
		//if (info==null)
		//	return;
		
		//info.cellX = cellX;
		//info.cellY = cellY;
		
		LayoutParams lp = (LayoutParams) v.getLayoutParams();
		if (lp == null)
			return;

		lp.cellX = cellX;
		lp.cellY = cellY;
		lp.x = x;
		lp.y = y;

		Log.d(TAG,"changeCellXY:" + v.toString());
	}

	public void changeCellXY(View v, int cellX, int cellY) {
		if (v == null)
			return;
		
		LayoutParams lp = (LayoutParams) v.getLayoutParams();
		if (lp == null)
			return;

		changeCellXY(v, cellX, cellY, lp.x, lp.y);

		//Log.d(TAG,"changeCellXY:" + v.toString());
	}

	
//	public void changeCellXY(View v, int cellX, int cellY, int x, int y) {
//		if (v == null)
//			return;
//
//		LayoutParams lp = (LayoutParams) v.getLayoutParams();
//		if (lp == null)
//			return;
//
//		lp.cellX = cellX;
//		lp.cellY = cellY;
//
//		lp.x = x;
//		lp.y = y;
//
//		Log.d(TAG, "changeCellXY::"+ lp.toString());
//	}

	int findNearestVacantCellBetween(int oldPlace, int newPlace) {
		int number = INVALID_CELL;

		if (oldPlace == newPlace) {
			number = newPlace;
		} else {
			number = oldPlace;

			int newCell[] = mCellXY;
			numberToCell(newPlace, newCell);

			int row = newCell[1];
			int col = newCell[0];

			int countX = getCountX();

			if (newPlace < oldPlace) {
				for (int i = newPlace + 1; i < oldPlace; i++) {

					col++;
					if (col >= countX) {
						row++;
						col = 0;
					}

					int index = cellToIndex(col, row);
					if (index < 0 || index >= getMaxCount()) {
						number = cellToNumber(col, row);
						break;
					}
				}
			} else if (newPlace > oldPlace) {
				for (int i = newPlace - 1; i > oldPlace; i--) {

					col--;
					if (col < 0) {
						row--;
						col = countX - 1;
					}

					int index = cellToIndex(col, row);
					if (index < 0 || index >= getMaxCount()) {
						number = cellToNumber(col, row);
						break;
					}
				}
			}
		}

		return number;
	}

	int findNearestVacantCellIn(int currentPlace) {
		int number = INVALID_CELL;

		// int temp1 = INVALID_CELL;
		// int temp2 = INVALID_CELL;

		// if (currentPlace>=(getMaxCount()>>2)) {
		final int temp1 = findNearestVacantCellIn(currentPlace, getMaxCount() - 1);
		// } else {
		final int temp2 = findNearestVacantCellIn(currentPlace, 0);
		// }

		if (temp1 >= 0 && temp2 >= 0) {
			number = (Math.abs(temp1 - currentPlace) < Math.abs(temp2
					- currentPlace)) ? temp1 : temp2;
		} else {
			if (temp1 < 0)
				number = temp2;
			if (temp2 < 0)
				number = temp1;
		}

		return number;
	}

	int findNearestVacantCellIn(int currentPlace, int newPlace) {

		if (currentPlace == newPlace)
			return INVALID_CELL;

		int result = INVALID_CELL;

		if (currentPlace < newPlace) {
			for (int i = currentPlace + 1; i <= newPlace; i++) {
				if (numberToIndex(i) < 0) {
					result = i;
					break;
				}
			}
		} else if (currentPlace > newPlace) {
			for (int i = currentPlace - 1; i >= newPlace; i--) {
				if (numberToIndex(i) < 0) {
					result = i;
					break;
				}
			}
		}

		return result;

	}

	CellInfo findAllVacantCells(boolean[] occupiedCells, View ignoreView) {
		final boolean portrait = mPortrait;
		final int xCount = portrait ? mShortAxisCells : mLongAxisCells;
		final int yCount = portrait ? mLongAxisCells : mShortAxisCells;

		boolean[][] occupied = mOccupied;

		if (occupiedCells != null) {
			for (int y = 0; y < yCount; y++) {
				for (int x = 0; x < xCount; x++) {
					occupied[x][y] = occupiedCells[y * xCount + x];
				}
			}
		} else {
			findOccupiedCells(xCount, yCount, occupied, ignoreView);
		}

		CellInfo cellInfo = new CellInfo();

		cellInfo.cellX = -1;
		cellInfo.cellY = -1;
		cellInfo.spanY = 0;
		cellInfo.spanX = 0;
		cellInfo.maxVacantSpanX = Integer.MIN_VALUE;
		cellInfo.maxVacantSpanXSpanY = Integer.MIN_VALUE;
		cellInfo.maxVacantSpanY = Integer.MIN_VALUE;
		cellInfo.maxVacantSpanYSpanX = Integer.MIN_VALUE;
		cellInfo.screen = ((ViewGroup) getParent()).indexOfChild(this);

		Rect current = cellInfo.current;

		for (int x = 0; x < xCount; x++) {
			for (int y = 0; y < yCount; y++) {
				if (!occupied[x][y]) {
					current.set(x, y, x, y);
					findVacantCell(current, xCount, yCount, occupied, cellInfo);
					occupied[x][y] = true;
				}
			}
		}

		cellInfo.valid = cellInfo.vacantCells.size() > 0;

		// Assume the caller will perform their own cell searching, otherwise we
		// risk causing an unnecessary rebuild after findCellForSpan()

		return cellInfo;
	}

	/**
	 * Given a point, return the cell that strictly encloses that point
	 * 
	 * @param x
	 *            X coordinate of the point
	 * @param y
	 *            Y coordinate of the point
	 * @param result
	 *            Array of 2 ints to hold the x and y coordinate of the cell
	 */
	void pointToCellExact(int x, int y, int[] result) {
		final boolean portrait = mPortrait;

		final int hStartPadding = portrait ? mShortAxisStartPadding
				: mLongAxisStartPadding;
		final int vStartPadding = portrait ? mLongAxisStartPadding
				: mShortAxisStartPadding;

		result[0] = (x - hStartPadding) / (mCellWidth + mWidthGap);
		result[1] = (y - vStartPadding) / (mCellHeight + mHeightGap);

		final int xAxis = portrait ? mShortAxisCells : mLongAxisCells;
		final int yAxis = portrait ? mLongAxisCells : mShortAxisCells;

		if (result[0] < 0) {
			result[0] = 0;
		}
		if (result[0] >= xAxis) {
			result[0] = xAxis - 1;
		}
		if (result[1] < 0) {
			result[1] = 0;
		}
		if (result[1] >= yAxis) {
			result[1] = yAxis - 1;
		}
	}

	/**
	 * Given a point, return the cell that most closely encloses that point
	 * 
	 * @param x
	 *            X coordinate of the point
	 * @param y
	 *            Y coordinate of the point
	 * @param result
	 *            Array of 2 ints to hold the x and y coordinate of the cell
	 */
	void pointToCellRounded(int x, int y, int[] result) {
		pointToCellExact(x + (mCellWidth / 2), y + (mCellHeight / 2), result);
	}

	int pointToNumber(int x, int y) { // 0-15//4*4=16
		int number = INVALID_CELL;

		int cellXY[] = mCellXY;
		pointToCellExact(x, y, cellXY);
		number = cellXY[1] * getCountX() + cellXY[0];

		return number;
	}

	/**
	 * Given a cell coordinate, return the point that represents the upper left
	 * corner of that cell
	 * 
	 * @param cellX
	 *            X coordinate of the cell
	 * @param cellY
	 *            Y coordinate of the cell
	 * 
	 * @param result
	 *            Array of 2 ints to hold the x and y coordinate of the point
	 */
	void cellToPoint(int cellX, int cellY, int[] result) {
		final boolean portrait = mPortrait;

		final int hStartPadding = portrait ? mShortAxisStartPadding
				: mLongAxisStartPadding;
		final int vStartPadding = portrait ? mLongAxisStartPadding
				: mShortAxisStartPadding;

		result[0] = hStartPadding + cellX * (mCellWidth + mWidthGap);
		result[1] = vStartPadding + cellY * (mCellHeight + mHeightGap);
	}

	void numberToPoint(int number, int[] result) {
		if (result == null)
			return;

		int cellXY[] = mCellXY;
		numberToCell(number, cellXY);
		cellToPoint(cellXY[0], cellXY[1], result);
	}

	int getCellWidth() {
		return mCellWidth;
	}

	int getCellHeight() {
		return mCellHeight;
	}

	int getLeftPadding() {
		return mPortrait ? mShortAxisStartPadding : mLongAxisStartPadding;
	}

	int getTopPadding() {
		return mPortrait ? mLongAxisStartPadding : mShortAxisStartPadding;
	}

	int getRightPadding() {
		return mPortrait ? mShortAxisEndPadding : mLongAxisEndPadding;
	}

	int getBottomPadding() {
		return mPortrait ? mLongAxisEndPadding : mShortAxisEndPadding;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		// TODO: currently ignoring padding
		int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
		int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
		int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
		int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

		if (widthSpecMode == MeasureSpec.UNSPECIFIED
				|| heightSpecMode == MeasureSpec.UNSPECIFIED) {
			throw new RuntimeException(
					"CellLayout cannot have UNSPECIFIED dimensions");
		}

		final int shortAxisCells = mShortAxisCells;
		final int longAxisCells = mLongAxisCells;
		final int longAxisStartPadding = mLongAxisStartPadding;
		final int longAxisEndPadding = mLongAxisEndPadding;
		final int shortAxisStartPadding = mShortAxisStartPadding;
		final int shortAxisEndPadding = mShortAxisEndPadding;
		final int cellWidth = mCellWidth;
		final int cellHeight = mCellHeight;

		mPortrait = heightSpecSize > widthSpecSize;

		int numShortGaps = shortAxisCells - 1;
		int numLongGaps = longAxisCells - 1;

		if (mPortrait) {
			int vSpaceLeft = heightSpecSize - longAxisStartPadding
					- longAxisEndPadding - (cellHeight * longAxisCells);
			mHeightGap = vSpaceLeft / numLongGaps;

			int hSpaceLeft = widthSpecSize - shortAxisStartPadding
					- shortAxisEndPadding - (cellWidth * shortAxisCells);
			if (numShortGaps > 0) {
				mWidthGap = hSpaceLeft / numShortGaps;
			} else {
				mWidthGap = 0;
			}
		} else {
			int hSpaceLeft = widthSpecSize - longAxisStartPadding
					- longAxisEndPadding - (cellWidth * longAxisCells);
			mWidthGap = hSpaceLeft / numLongGaps;

			int vSpaceLeft = heightSpecSize - shortAxisStartPadding
					- shortAxisEndPadding - (cellHeight * shortAxisCells);
			if (numShortGaps > 0) {
				mHeightGap = vSpaceLeft / numShortGaps;
			} else {
				mHeightGap = 0;
			}
		}

		int count = getChildCount();

		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			LayoutParams lp = (LayoutParams) child.getLayoutParams();

			if (mPortrait) {
				lp.setup(cellWidth, cellHeight, mWidthGap, mHeightGap,
						shortAxisStartPadding, longAxisStartPadding);
			} else {
				lp.setup(cellWidth, cellHeight, mWidthGap, mHeightGap,
						longAxisStartPadding, shortAxisStartPadding);
			}

			if (lp.regenerateId) {
				child.setId(((getId() & 0xFF) << 16) | (lp.cellX & 0xFF) << 8
						| (lp.cellY & 0xFF));
				lp.regenerateId = false;
			}

			int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(lp.width,
					MeasureSpec.EXACTLY);
			int childheightMeasureSpec = MeasureSpec.makeMeasureSpec(lp.height,
					MeasureSpec.EXACTLY);
			child.measure(childWidthMeasureSpec, childheightMeasureSpec);
		}

		setMeasuredDimension(widthSpecSize, heightSpecSize);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int count = getChildCount();

		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() != GONE) {
				CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child
						.getLayoutParams();

				int childLeft = lp.x;	
				Workspace workspace = (Workspace) getParent();
				if (workspace.ismStartDrag() && !(workspace.getLauncher().mDeleteZone.getVisibility() == View.VISIBLE)) {
					lp.y -= workspace.getmHeightStatusBar();
				}
				int childTop = lp.y;
				child.layout(childLeft, childTop, childLeft + lp.width,
						childTop + lp.height);

				Log.d(TAG, "onLayout,(X,Y),(W,H)="+childLeft+","+childTop+","+lp.width+","+lp.height);
				
				if (lp.dropped) {
					lp.dropped = false;

					final int[] cellXY = mCellXY;
					getLocationOnScreen(cellXY);
					mWallpaperManager.sendWallpaperCommand(getWindowToken(),
							"android.home.drop", cellXY[0] + childLeft
									+ lp.width / 2, cellXY[1] + childTop
									+ lp.height / 2, 0, null);
				}
			}
		}
	}

	@Override
	protected void setChildrenDrawingCacheEnabled(boolean enabled) {
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			final View view = getChildAt(i);
			view.setDrawingCacheEnabled(enabled);
			// Update the drawing caches
			view.buildDrawingCache(true);
		}
	}

	@Override
	protected void setChildrenDrawnWithCacheEnabled(boolean enabled) {
		super.setChildrenDrawnWithCacheEnabled(enabled);
	}

	/**
	 * Find a vacant area that will fit the given bounds nearest the requested
	 * cell location. Uses Euclidean distance to score multiple vacant areas.
	 * 
	 * @param pixelX
	 *            The X location at which you want to search for a vacant area.
	 * @param pixelY
	 *            The Y location at which you want to search for a vacant area.
	 * @param spanX
	 *            Horizontal span of the object.
	 * @param spanY
	 *            Vertical span of the object.
	 * @param vacantCells
	 *            Pre-computed set of vacant cells to search.
	 * @param recycle
	 *            Previously returned value to possibly recycle.
	 * @return The X, Y cell of a vacant area that can contain this object,
	 *         nearest the requested location.
	 */
	int[] findNearestVacantArea(int pixelX, int pixelY, int spanX, int spanY,
			CellInfo vacantCells, int[] recycle) {
		// Keep track of best-scoring drop area
		final int[] bestXY = recycle != null ? recycle : new int[2];
		final int[] cellXY = mCellXY;
		double bestDistance = Double.MAX_VALUE;

		// Bail early if vacant cells aren't valid
		if (!vacantCells.valid) {
			return null;
		}

		// Look across all vacant cells for best fit
		final int size = vacantCells.vacantCells.size();
		for (int i = 0; i < size; i++) {
			final CellInfo.VacantCell cell = vacantCells.vacantCells.get(i);

			// Reject if vacant cell isn't our exact size
			if (cell.spanX != spanX || cell.spanY != spanY) {
				continue;
			}

			// Score is center distance from requested pixel
			cellToPoint(cell.cellX, cell.cellY, cellXY);

			double distance = Math.sqrt(Math.pow(cellXY[0] - pixelX, 2)
					+ Math.pow(cellXY[1] - pixelY, 2));
			if (distance <= bestDistance) {
				bestDistance = distance;
				bestXY[0] = cell.cellX;
				bestXY[1] = cell.cellY;
			}
		}

		// Return null if no suitable location found
		if (bestDistance < Double.MAX_VALUE) {
			return bestXY;
		} else {
			return null;
		}
	}

	void onDropChild(View child) {
		if (child != null) {
			LayoutParams lp = (LayoutParams) child.getLayoutParams();
			lp.isDragging = false;
			lp.dropped = true;
			mDragRect.setEmpty();
		}
	}
	
	/**
	 * Drop a child at the specified position
	 * 
	 * @param child
	 *            The child that is being dropped
	 * @param targetXY
	 *            Destination area to move to
	 */
	void onDropChild(View child, int[] targetXY) {
		if (targetXY == null)
			return;

		if (child != null) {
			LayoutParams lp = (LayoutParams) child.getLayoutParams();
			lp.cellX = targetXY[0];
			lp.cellY = targetXY[1];
			lp.isDragging = false;
			lp.dropped = true;
			mDragRect.setEmpty();
			child.requestLayout();
			invalidate();
		}
	}

	void onDropAborted(View child) {
		if (child != null) {
			((LayoutParams) child.getLayoutParams()).isDragging = false;
			invalidate();
		}
		mDragRect.setEmpty();
	}

	/**
	 * Start dragging the specified child
	 * 
	 * @param child
	 *            The child that is being dragged
	 */
	void onDragChild(View child) {
		if (child != null) {
			LayoutParams lp = (LayoutParams) child.getLayoutParams();
			lp.isDragging = true;
			mDragRect.setEmpty();
		}
	}

	/**
	 * Drag a child over the specified position
	 * 
	 * @param child
	 *            The child that is being dropped
	 * @param cellX
	 *            The child's new x cell location
	 * @param cellY
	 *            The child's new y cell location
	 */
	void onDragOverChild(View child, int cellX, int cellY) {
		if (child != null) {
			int[] cellXY = mCellXY;
			pointToCellRounded(cellX, cellY, cellXY);
			LayoutParams lp = (LayoutParams) child.getLayoutParams();
			cellToRect(cellXY[0], cellXY[1], lp.cellHSpan, lp.cellVSpan,
					mDragRect);
			invalidate();
		}
	}

	/**
	 * Computes a bounding rectangle for a range of cells
	 * 
	 * @param cellX
	 *            X coordinate of upper left corner expressed as a cell position
	 * @param cellY
	 *            Y coordinate of upper left corner expressed as a cell position
	 * @param cellHSpan
	 *            Width in cells
	 * @param cellVSpan
	 *            Height in cells
	 * @param dragRect
	 *            Rectnagle into which to put the results
	 */
	public void cellToRect(int cellX, int cellY, int cellHSpan, int cellVSpan,
			RectF dragRect) {
		final boolean portrait = mPortrait;
		final int cellWidth = mCellWidth;
		final int cellHeight = mCellHeight;
		final int widthGap = mWidthGap;
		final int heightGap = mHeightGap;

		final int hStartPadding = portrait ? mShortAxisStartPadding
				: mLongAxisStartPadding;
		final int vStartPadding = portrait ? mLongAxisStartPadding
				: mShortAxisStartPadding;

		int width = cellHSpan * cellWidth + ((cellHSpan - 1) * widthGap);
		int height = cellVSpan * cellHeight + ((cellVSpan - 1) * heightGap);

		int x = hStartPadding + cellX * (cellWidth + widthGap);
		int y = vStartPadding + cellY * (cellHeight + heightGap);

		dragRect.set(x, y, x + width, y + height);
	}

	/**
	 * Computes the required horizontal and vertical cell spans to always fit
	 * the given rectangle.
	 * 
	 * @param width
	 *            Width in pixels
	 * @param height
	 *            Height in pixels
	 */
	public int[] rectToCell(int width, int height) {
		// Always assume we're working with the smallest span to make sure we
		// reserve enough space in both orientations.
		int actualWidth;
		int actualHeight;
		
		try {
			final Resources resources = getResources();
			actualWidth = resources
					.getDimensionPixelSize(R.dimen.workspace_cell_width);
			actualHeight = resources
					.getDimensionPixelSize(R.dimen.workspace_cell_height);
		} catch (NotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			actualWidth = 86;
			actualHeight = 116;
		}
		
		
		int smallerSize = Math.min(actualWidth, actualHeight);

		// Always round up to next largest cell
//		int spanX = (width + smallerSize) / smallerSize;
//		int spanY = (height + smallerSize) / smallerSize;
        int spanX = (int) Math.ceil(width / (float) smallerSize);
        int spanY = (int) Math.ceil(height / (float) smallerSize);
        
		if (spanX > 4)
			spanX = 4;
		if (spanY > 4)
			spanY = 4;

		return new int[] { spanX, spanY };
	}

	/**
	 * Find the first vacant cell, if there is one.
	 * 
	 * @param vacant
	 *            Holds the x and y coordinate of the vacant cell
	 * @param spanX
	 *            Horizontal cell span.
	 * @param spanY
	 *            Vertical cell span.
	 * 
	 * @return True if a vacant cell was found
	 */
	public boolean getVacantCell(int[] vacant, int spanX, int spanY) {
		final boolean portrait = mPortrait;
		final int xCount = portrait ? mShortAxisCells : mLongAxisCells;
		final int yCount = portrait ? mLongAxisCells : mShortAxisCells;
		final boolean[][] occupied = mOccupied;

		findOccupiedCells(xCount, yCount, occupied, null);

		return findVacantCell(vacant, spanX, spanY, xCount, yCount, occupied);
	}

	static boolean findVacantCell(int[] vacant, int spanX, int spanY,
			int xCount, int yCount, boolean[][] occupied) {

		for (int y = 0; y < yCount; y++) {
			for (int x = 0; x < xCount; x++) {
				boolean available = !occupied[x][y];
				out: for (int j = y; j < y + spanY - 1 && y < yCount; j++) {
					for (int i = x; i < x + spanX - 1 && x < xCount; i++) {					
						available = available && !occupied[i][j];
						if (!available)
							break out;
					}
				}

				if (available) {
					vacant[0] = x;
					vacant[1] = y;
					return true;
				}
			}
		}

		return false;
	}

	boolean[] getOccupiedCells() {
		final boolean portrait = mPortrait;
		final int xCount = portrait ? mShortAxisCells : mLongAxisCells;
		final int yCount = portrait ? mLongAxisCells : mShortAxisCells;
		final boolean[][] occupied = mOccupied;

		findOccupiedCells(xCount, yCount, occupied, null);

		final boolean[] flat = new boolean[xCount * yCount];
		for (int y = 0; y < yCount; y++) {
			for (int x = 0; x < xCount; x++) {
				flat[y * xCount + x] = occupied[x][y];
			}
		}

		return flat;
	}

	private void findOccupiedCells(int xCount, int yCount,
			boolean[][] occupied, View ignoreView) {
		for (int x = 0; x < xCount; x++) {
			for (int y = 0; y < yCount; y++) {
				occupied[x][y] = false;
			}
		}

		int count = getChildCount();
		
		try {
			for (int i = 0; i < count; i++) {
				View child = getChildAt(i);
				if (child instanceof Folder || child.equals(ignoreView)) {
					continue;
				}
				LayoutParams lp = (LayoutParams) child.getLayoutParams();

				for (int x = lp.cellX; x < lp.cellX + lp.cellHSpan && x < xCount; x++) {
					for (int y = lp.cellY; y < lp.cellY + lp.cellVSpan
							&& y < yCount; y++) {
						occupied[x][y] = true;
					}
				}
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
		return new CellLayout.LayoutParams(getContext(), attrs);
	}

	@Override
	protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
		return p instanceof CellLayout.LayoutParams;
	}

	@Override
	protected ViewGroup.LayoutParams generateLayoutParams(
			ViewGroup.LayoutParams p) {
		return new CellLayout.LayoutParams(p);
	}

	public static class LayoutParams extends ViewGroup.MarginLayoutParams {

		/**
		 * Horizontal location of the item in the grid.
		 */
		@ViewDebug.ExportedProperty
		public int cellX;

		/**
		 * Vertical location of the item in the grid.
		 */
		@ViewDebug.ExportedProperty
		public int cellY;

		/**
		 * Number of cells spanned horizontally by the item.
		 */
		@ViewDebug.ExportedProperty
		public int cellHSpan;

		/**
		 * Number of cells spanned vertically by the item.
		 */
		@ViewDebug.ExportedProperty
		public int cellVSpan;

		/**
		 * Is this item currently being dragged
		 */
		public boolean isDragging;

		// X coordinate of the view in the layout.
		@ViewDebug.ExportedProperty
		int x;

		// Y coordinate of the view in the layout.
		@ViewDebug.ExportedProperty
		int y;

		boolean regenerateId;

		boolean dropped;

		public LayoutParams(Context c, AttributeSet attrs) {
			super(c, attrs);
			cellHSpan = 1;
			cellVSpan = 1;
		}

		public LayoutParams(ViewGroup.LayoutParams source) {
			super(source);
			cellHSpan = 1;
			cellVSpan = 1;
		}

		public LayoutParams(int cellX, int cellY, int cellHSpan, int cellVSpan) {
			super(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
					android.view.ViewGroup.LayoutParams.MATCH_PARENT);
			this.cellX = cellX;
			this.cellY = cellY;
			this.cellHSpan = cellHSpan;
			this.cellVSpan = cellVSpan;
		}

		public void setup(int cellWidth, int cellHeight, int widthGap,
				int heightGap, int hStartPadding, int vStartPadding) {
			final int myCellHSpan = cellHSpan;
			final int myCellVSpan = cellVSpan;
			final int myCellX = cellX;
			final int myCellY = cellY;

			width = myCellHSpan * cellWidth + ((myCellHSpan - 1) * widthGap)
					- leftMargin - rightMargin;
			height = myCellVSpan * cellHeight + ((myCellVSpan - 1) * heightGap)
					- topMargin - bottomMargin;

			x = hStartPadding + myCellX * (cellWidth + widthGap) + leftMargin;
			y = vStartPadding + myCellY * (cellHeight + heightGap) + topMargin;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
		    String str = null;
            
			// TODO Auto-generated method stub
		    
			try {
				str = "cell(" + this.cellX + "," + this.cellY + ","
						+ this.cellHSpan + "," + this.cellVSpan + ")";
				str += "pos(" + this.x + "," + this.y + "," + this.width + ","
						+ this.height + ")";
				str += ", isDragging=" + this.isDragging;
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			return str;// super.toString();
		}
	}

	static final class CellInfo implements ContextMenu.ContextMenuInfo {
		/**
		 * See View.AttachInfo.InvalidateInfo for futher explanations about the
		 * recycling mechanism. In this case, we recycle the vacant cells
		 * instances because up to several hundreds can be instanciated when the
		 * user long presses an empty cell.
		 */
		static final class VacantCell {
			int cellX;
			int cellY;
			int spanX;
			int spanY;

			// We can create up to 523 vacant cells on a 4x4 grid, 100 seems
			// like a reasonable compromise given the size of a VacantCell and
			// the fact that the user is not likely to touch an empty 4x4 grid
			// very often
			private static final int POOL_LIMIT = 100;
			private static final Object sLock = new Object();

			private static int sAcquiredCount = 0;
			private static VacantCell sRoot;

			private VacantCell next;

			static VacantCell acquire() {
				synchronized (sLock) {
					if (sRoot == null) {
						return new VacantCell();
					}

					VacantCell info = sRoot;
					sRoot = info.next;
					sAcquiredCount--;

					return info;
				}
			}

			void release() {
				synchronized (sLock) {
					if (sAcquiredCount < POOL_LIMIT) {
						sAcquiredCount++;
						next = sRoot;
						sRoot = this;
					}
				}
			}

			@Override
			public String toString() {
				return "VacantCell[x=" + cellX + ", y=" + cellY + ", spanX="
						+ spanX + ", spanY=" + spanY + "]";
			}
		}

		View cell;
		int cellX;
		int cellY;
		int spanX;
		int spanY;
		int screen;
		boolean valid;

		final ArrayList<VacantCell> vacantCells = new ArrayList<VacantCell>(
				VacantCell.POOL_LIMIT);
		int maxVacantSpanX;
		int maxVacantSpanXSpanY;
		int maxVacantSpanY;
		int maxVacantSpanYSpanX;
		final Rect current = new Rect();

		void clearVacantCells() {
			final ArrayList<VacantCell> list = vacantCells;
			final int count = list.size();

			for (int i = 0; i < count; i++) {
				list.get(i).release();
			}

			list.clear();
		}

		void findVacantCellsFromOccupied(boolean[] occupied, int xCount,
				int yCount) {
			if (cellX < 0 || cellY < 0) {
				maxVacantSpanX = maxVacantSpanXSpanY = Integer.MIN_VALUE;
				maxVacantSpanY = maxVacantSpanYSpanX = Integer.MIN_VALUE;
				clearVacantCells();
				return;
			}

			final boolean[][] unflattened = new boolean[xCount][yCount];
			for (int y = 0; y < yCount; y++) {
				for (int x = 0; x < xCount; x++) {
					unflattened[x][y] = occupied[y * xCount + x];
				}
			}
			CellLayout.findIntersectingVacantCells(this, cellX, cellY, xCount,
					yCount, unflattened);
		}

		/**
		 * This method can be called only once! Calling
		 * #findVacantCellsFromOccupied will restore the ability to call this
		 * method.
		 * 
		 * Finds the upper-left coordinate of the first rectangle in the grid
		 * that can hold a cell of the specified dimensions.
		 * 
		 * @param cellXY
		 *            The array that will contain the position of a vacant cell
		 *            if such a cell can be found.
		 * @param spanX
		 *            The horizontal span of the cell we want to find.
		 * @param spanY
		 *            The vertical span of the cell we want to find.
		 * 
		 * @return True if a vacant cell of the specified dimension was found,
		 *         false otherwise.
		 */
		boolean findCellForSpan(int[] cellXY, int spanX, int spanY) {
			return findCellForSpan(cellXY, spanX, spanY, true);
		}

		boolean findCellForSpan(int[] cellXY, int spanX, int spanY,
				boolean clear) {
			final ArrayList<VacantCell> list = vacantCells;
			final int count = list.size();

			boolean found = false;

			if (this.spanX >= spanX && this.spanY >= spanY) {
				cellXY[0] = cellX;
				cellXY[1] = cellY;
				found = true;
			}

			// Look for an exact match first
			for (int i = 0; i < count; i++) {
				VacantCell cell = list.get(i);
				if (cell.spanX == spanX && cell.spanY == spanY) {
					cellXY[0] = cell.cellX;
					cellXY[1] = cell.cellY;
					found = true;
					break;
				}
			}

			// Look for the first cell large enough
			for (int i = 0; i < count; i++) {
				VacantCell cell = list.get(i);
				if (cell.spanX >= spanX && cell.spanY >= spanY) {
					cellXY[0] = cell.cellX;
					cellXY[1] = cell.cellY;
					found = true;
					break;
				}
			}

			if (clear) {
				clearVacantCells();
			}

			return found;
		}

		@Override
		public String toString() {
			return "Cell[view=" + (cell == null ? "null" : cell.getClass())
					+ ", x=" + cellX + ", y=" + cellY + "],screen="+screen;
		}
	}

	public boolean lastDownOnOccupiedCell() {
		return mLastDownOnOccupiedCell;
	}

	@Override
	protected boolean getChildStaticTransformation(View child, Transformation t) {
		// TODO Auto-generated method stub
		EffectBase effect = EffectsFactory
				.getEffectByType(SettingUtils.mTransitionEffect);
		if (effect == null) {
			return false;
		}
		Workspace workspace = (Workspace) getParent();
		float ratio = workspace.getCurrentScrollRatio(this);
		return effect.getCellLayoutChildStaticTransformation(this, child, t,
				mCamera, ratio, workspace.mCurrentScreen, getBottomPadding(),
				true);
	}

	@Override
	public void setStaticTransformationsEnabled(boolean enabled) {
		super.setStaticTransformationsEnabled(enabled);
	}

	final void switchScreenMode(boolean bIsFullScreen, int paddingTop) {
		if (mIsFullScreen != bIsFullScreen) {
			final int padding = bIsFullScreen ? paddingTop : -paddingTop;
			if (mPortrait) {
				mLongAxisStartPadding += padding;
			} else {
				mShortAxisStartPadding += padding;
			}
			mIsFullScreen = bIsFullScreen;
		}
	}

	/**
	 * @return the pageIndex
	 */
	public int getPageIndex() {
		return pageIndex;
	}

	/**
	 * @param pageIndex
	 *            the pageIndex to set
	 */
	public void setPageIndex(int pageIndex) {
		this.pageIndex = pageIndex;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		// TODO Auto-generated method stub
		String str = null;

        try {
			str = ("Have item childs="+getChildCount()+",pageIndex="+getPageIndex());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
		return str;
	}
	
	int getShortcutCount(ShortcutInfo info){
		int counter = 0;
		
		try {			
			final String title = info.title.toString();
			//final String className = info.intent.getComponent().getClassName();			
			final String pkgName = Launcher.getPackageName(info);
			final int count = getChildCount();
			
			for(int j = 0; j < count; j++){
				final View v = getChildAt(j);
				Object tag = v.getTag();
				if (tag instanceof UserFolderInfo) {
					final UserFolderInfo folderInfo = (UserFolderInfo)tag;
					final int folder_size = folderInfo.getSize();
					for (int k = 0;k<folder_size;k++){
						final ShortcutInfo sInfo = (ShortcutInfo) folderInfo.contents.get(k);
						if (sInfo.itemType==Favorites.ITEM_TYPE_APPLICATION){	
							//final String eachClassName = sInfo.intent.getComponent().getClassName();
							final String eachPkgName = Launcher.getPackageName(sInfo);
							if (title.equals(sInfo.title.toString()) && pkgName.equals(eachPkgName)) {
								counter++;
							} 
						} 
					}
				} else if (tag instanceof ShortcutInfo){
					final ShortcutInfo sInfo = (ShortcutInfo)tag;						
					if (sInfo.itemType==Favorites.ITEM_TYPE_APPLICATION) {
						//final String eachClassName = sInfo.intent.getComponent().getClassName();
						final String eachPkgName = Launcher.getPackageName(sInfo);
						if (title.equals(sInfo.title.toString()) && pkgName.equals(eachPkgName)) {
							counter++;
						} 
					}
				} 
			}
		
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();			
		} 		
			
		return counter;	
	}
	
	boolean hasShortcut(ShortcutInfo info, Intent data) {
		try {			
			final String title = info.title.toString();
			final String className = Launcher.getClassName(info, data);			
			final int count = getChildCount();
			
			for(int j = 0; j < count; j++){
				final View v = getChildAt(j);
				Object tag = v.getTag();
				if (tag instanceof UserFolderInfo) {
					final UserFolderInfo folderInfo = (UserFolderInfo)tag;
					final int folder_size = folderInfo.getSize();
					for (int k = 0;k<folder_size;k++){
						final ShortcutInfo sInfo = (ShortcutInfo) folderInfo.contents.get(k);
						if (sInfo.itemType==Favorites.ITEM_TYPE_APPLICATION){	
							final String eachClassName = Launcher.getClassName(sInfo);														
							if (title.equals(sInfo.title.toString()) && className.equals(eachClassName)) {
								return true;
							} 
						} 
					}
				} else if (tag instanceof ShortcutInfo){
					final ShortcutInfo sInfo = (ShortcutInfo)tag;						
					if (sInfo.itemType==Favorites.ITEM_TYPE_APPLICATION) {
						final String eachClassName = Launcher.getClassName(sInfo);
						if (title.equals(sInfo.title.toString()) && className.equals(eachClassName)) {
							return true;
						} 
					}
				} 
			}
		
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();			
		} 
		
		
		return false;

	}
}