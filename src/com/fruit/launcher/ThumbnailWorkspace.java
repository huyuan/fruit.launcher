package com.fruit.launcher;

import com.fruit.launcher.setting.SettingUtils;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

public class ThumbnailWorkspace extends ViewGroup {

	private static final String TAG = "ThumbnailWorkspace";
	private static final int ROW_NUM = 3;
	private static final int COL_NUM = 3;
	private static final int MAX_COUNT = ROW_NUM * COL_NUM; 

	private Context mContext;
	private LayoutInflater mInflater;
	private Launcher mLauncher;
	private Workspace mWorkspace;
	private boolean mIsVisible;
	private View mAddScreen;
	private int mWidthStartPadding;
	private int mWidthEndPadding;
	private int mHeightStartPadding;
	private int mHeightEndPadding;
	private int mThumbWidth;
	private int mThumbHeight;

	private int mDeleteScreenIndex;
	private boolean mReachMax;
	private boolean mStartDrag;

	private View.OnClickListener mThumbClickListener;
	private View.OnClickListener mAddScreenClickListener;
	private View.OnClickListener mDeleteScreenClickListener;
	private View.OnClickListener mHomeClickListener;
	private View.OnLongClickListener mLongClickListener;

	private View mDragView;
	private int mDragInitPos;
	private int mFromPos;
	private int mToPos;
	private int[] mPos = new int[2];
	private ItemAnimate mItemAnimate;

	private int mCurSelectedScreenIndex;
	
	
	public ThumbnailWorkspace(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
		// TODO Auto-generated constructor stub
	}

	public ThumbnailWorkspace(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		mItemAnimate = new ItemAnimate(context);
		// TODO Auto-generated constructor stub
		mIsVisible = false;
		mReachMax = false;
		mStartDrag = false;
		initWorkspace(context);
	}

//	private int getChildIndexByPos(int pageIndex){
//		int result = -1;
//		
//		for (int i = 0; i < mWorkspace.getChildCount(); i++) {
//			CellLayout cell = (CellLayout) mWorkspace.getChildAt(i);
//			if(cell.getPageIndex()==pageIndex)
//				result= i;
//		}
//		
//		return result;
//		
//	}
	
//	private void setFocusScreen(int pos){
//		if(pos<getWorkspaceFocusIndex())
//			mWorkspace.setmTouchDirection(mWorkspace.TOUCH_STATE_SCROLLING_RIGHT);
//		else if(pos>getWorkspaceFocusIndex())
//			mWorkspace.setmTouchDirection(mWorkspace.TOUCH_STATE_SCROLLING_RIGHT);
//		
//		//pos = SettingUtils.mFixedScreenIndex; //yfzhao
//		int index = getIndexByPos(pos);
//		//mWorkspace.mCurrentScreen = SettingUtils.mHomeScreenIndex;//pos;
//		Launcher.setScreen(index);		
//		mWorkspace.snapToScreen(index);
//		mWorkspace.setmTouchDirection(0);
//	}
	
	private void setFocusScreen(int pos){
		//first scoll screen		
		//then set the screen
		int currPos = getWorkspaceFocusIndex();
		
		if(pos<currPos){
			//mWorkspace.setmTouchDirection(mWorkspace.TOUCH_STATE_SCROLLING_RIGHT);
			mWorkspace.changChildWhenScrollLeft(currPos-pos);
		} else if(pos>currPos){
			//mWorkspace.setmTouchDirection(mWorkspace.TOUCH_STATE_SCROLLING_RIGHT);
			mWorkspace.changChildWhenScrollRight(pos-currPos);
		}
		
		//pos = SettingUtils.mFixedScreenIndex; //yfzhao
		//int index = getIndexByPos(pos);
		//mWorkspace.mCurrentScreen = SettingUtils.mHomeScreenIndex;//pos;
		//Launcher.setScreen(index);		
		//mWorkspace.snapToScreen(index);
		mWorkspace.moveToScreen(mWorkspace.mCurrentScreen);
		//mWorkspace.setmTouchDirection(0);
	}
	
	private int getWorkspaceFocusIndex(){
		if(mWorkspace != null) {
			CellLayout layout = (CellLayout)mWorkspace.getChildAt(mWorkspace.mCurrentScreen);
			//this.mCurSelectedScreenIndex=layout.getPageIndex();
			return layout.getPageIndex();//mWorkspace.mCurrentScreen;
		}else {
			return 0;
		}
	}
	
	private void initWorkspace(Context context) {
		// TODO Auto-generated method stub
		mContext = context;
		mInflater = LayoutInflater.from(context);

		mCurSelectedScreenIndex = getWorkspaceFocusIndex();
		final Resources r = context.getResources();
		mWidthStartPadding = (int) r.getDimension(R.dimen.thumbnail_width_padding_start);
		mWidthEndPadding = (int) r.getDimension(R.dimen.thumbnail_width_padding_end);
		mHeightStartPadding = (int) r.getDimension(R.dimen.thumbnail_height_padding_start);
		mHeightEndPadding = (int) r.getDimension(R.dimen.thumbnail_height_padding_end);

		mThumbClickListener = new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				int mCurSnapScreenIndex = findViewIndex(v, 0);

				if (mCurSnapScreenIndex == -1) {
					// If not find the corrent view, just do nothing
					return;
				}
				mCurSelectedScreenIndex = mCurSnapScreenIndex;
				show(false, true);
			}
		};

		mAddScreenClickListener = new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				int childIndex = mWorkspace.getChildIndexByPos(0);
				mWorkspace.addNewScreen(childIndex);

				//final int newScreenIndex = mWorkspace.getChildCount() - 1;				
				View newScreenThumb =
					generateThumbView((CellLayout) mWorkspace.getChildAt(childIndex), childIndex/*newScreenIndex*/);

				if (mWorkspace.getChildCount() == MAX_COUNT) {
					mReachMax = true;
					removeView(mAddScreen);
					addView(newScreenThumb);
				} else {
					addView(newScreenThumb, (ThumbnailWorkspace.this.getChildCount() - 1));
				}
			}
		};

		mDeleteScreenClickListener = new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				int deleteScreenIndex = -1;//0;

				if (mWorkspace.getChildCount() == 1) {
					Toast.makeText(mContext, R.string.delete_screen_error_one, Toast.LENGTH_SHORT).show();
					return;
				}

				deleteScreenIndex = findViewIndex(v, R.id.thumbnail_delete_screen);

				if (deleteScreenIndex == -1) {
					// If not find the corrent view, just do nothing
					return;
				}

				if (deleteScreenIndex == SettingUtils.mHomeScreenIndex) {
					Toast.makeText(mContext, R.string.delete_screen_error_home, Toast.LENGTH_SHORT).show();
					return;
				}

				mDeleteScreenIndex = deleteScreenIndex;

				CellLayout cell = (CellLayout) mWorkspace.getChildAt(mWorkspace.getChildIndexByPos(deleteScreenIndex));
				if (cell.getChildCount() > 0) {
//					new AlertDialog.Builder(mContext)
//						.setIcon(android.R.drawable.ic_dialog_alert)
//						.setTitle(R.string.delete_screen_confirm_title)
//						.setMessage(R.string.delete_screen_confirm_msg)
//						.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
//
//							@Override
//							public void onClick(DialogInterface dialog, int which) {
//								// TODO Auto-generated method stub
//								processDeleteScreen();
//							}
//						})
//						.setNegativeButton(android.R.string.no, null)
//						.show();
					Toast.makeText(mContext, R.string.delete_screen_confirm_msg, Toast.LENGTH_SHORT).show();
					return;
				} else {
					processDeleteScreen();
				}
			}
		};

		if(!getResources().getBoolean(R.bool.config_lock_apps)){
			mHomeClickListener = new View.OnClickListener() {
	
				@Override
				public void onClick(View v) {
					// TODO Auto-generated method stub
					int newHomeIndex = findViewIndex(v, R.id.thumbnail_home_indicator);
					int oldHomeIndex = SettingUtils.mHomeScreenIndex;
	
					if (newHomeIndex == -1) {
						// If not find the current view, just do nothing
						return;
					}
	
					if (newHomeIndex != oldHomeIndex) {
						// Set old home indicator off, and set new home indicator on
						ThumbnailWorkspace mThumbnailWorkspace = ThumbnailWorkspace.this;
						ImageView oldHome =
							(ImageView) mThumbnailWorkspace.getChildAt(oldHomeIndex).findViewById(R.id.thumbnail_home_indicator);
						oldHome.setImageResource(R.drawable.ic_homescreen_none);
	
						ImageView newHome = (ImageView) v;
						newHome.setImageResource(R.drawable.ic_homescreen_on);
	
						mWorkspace.setDefaultScreen(newHomeIndex);
					}
				}
			};
		}

		mLongClickListener = new View.OnLongClickListener() {

			@Override
			public boolean onLongClick(View v) {
				// TODO Auto-generated method stub
				mStartDrag = true;
				mDragInitPos = findViewIndex(v, 0);
				mFromPos = mDragInitPos;
				mToPos = mDragInitPos;
				removeView(mAddScreen);
				mDragView = v;
				v.getParent().bringChildToFront(v);
				v.layout(getPosX(mPos[0]), getPosY(mPos[1]), getPosX(mPos[0]) + mThumbWidth, getPosY(mPos[1]) + mThumbHeight);
				return true;
			}
		};
	};

	private int getPosX(int x) {
		return x - mThumbWidth / 2;
	}

	private int getPosY(int y) {
		return y - mThumbHeight / 2;
	}

	private int findViewIndex(View v, int viewId) {
		// TODO Auto-generated method stub
		int index = -1;

		for (int i = 0; i < getChildCount(); i++) {
			View child = (viewId == 0) ? getChildAt(i) : getChildAt(i).findViewById(viewId);
			if (child == v) {
				index = i;
				break;
			}
		}
		return index;
	}

	private void processDeleteScreen() {
		// TODO Auto-generated method stub
		final int deleteScreenIndex = mDeleteScreenIndex;
		if (deleteScreenIndex == mCurSelectedScreenIndex) {
			// When current screen be deleted, select first thumb view
			getChildAt(0).setSelected(true);
			mCurSelectedScreenIndex = 0;
		}else if(deleteScreenIndex < mCurSelectedScreenIndex){
				mCurSelectedScreenIndex--;
		}
		
		mWorkspace.removeScreenAt(mWorkspace.getChildIndexByPos(deleteScreenIndex), deleteScreenIndex);
		removeViewAt(deleteScreenIndex);
		
		// Delete from 9 -> 8, need to add mAddScreen to thumb workspace
		if (mReachMax) {
			if (mAddScreen == null) {
				mAddScreen = mInflater.inflate(R.layout.thumbnail_add_screen, null);
				mAddScreen.setOnClickListener(mAddScreenClickListener);
			}
			
			mReachMax = false;
			addView(mAddScreen);
		}
	}

	public void setLauncher(Launcher launcher) {
		mLauncher = launcher;
	}

	public void setWorkspace(Workspace workspace) {
		mWorkspace = workspace;
		mCurSelectedScreenIndex = getWorkspaceFocusIndex();
	}

	public boolean isVisible() {
		return mIsVisible;
	}

	public boolean isUnderDrag() {
		return mStartDrag;
	}

	public void show(boolean shown, boolean animate) {
		// TODO Auto-generated method stub
		cancelLongPress();
		
		mWorkspace.printChildCount();
		
		mIsVisible = shown;
		if (mIsVisible) {
			mDragView = null;
			// Initialize thumb workspace with all cell layouts
			initThumbnail();
			// Show thumb workspace as visible
			getParent().bringChildToFront(this);
			setVisibility(View.VISIBLE);
			if (animate) {
				startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.zoom_in));
			} else {
				onAnimationEnd();
			}
		} else {
			setFocusScreen(mCurSelectedScreenIndex);
			if (animate) {
				startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.zoom_out));
			} else {
				onAnimationEnd();
			}
		}
	}

	private void initThumbnail() {
		// TODO Auto-generated method stub
		removeAllViews();

		mReachMax = mWorkspace.getChildCount() == MAX_COUNT;
		
		View thumbViews[] = new View[mWorkspace.getChildCount()];
		for (int i = 0; i < mWorkspace.getChildCount(); i++) {
			CellLayout cell = (CellLayout) mWorkspace.getChildAt(i);
			thumbViews[cell.getPageIndex()] = generateThumbView(cell, i/*cell.getPageIndex()*/);
		}
		
		for(int j=0;j<thumbViews.length;j++){
			addView(thumbViews[j]);
		}

		if (!mReachMax) {
			if (mAddScreen == null) {
				mAddScreen = mInflater.inflate(R.layout.thumbnail_add_screen, null);
				mAddScreen.setOnClickListener(mAddScreenClickListener);
			}

			addView(mAddScreen);
		}
	}

	private View generateThumbView(CellLayout cell, int index) {
		View view = mInflater.inflate(R.layout.thumbnail_item, null);
		ImageView thumb = (ImageView) view.findViewById(R.id.thumbnail_imageview);
		ImageView home = (ImageView) view.findViewById(R.id.thumbnail_home_indicator);
		ImageView delete = (ImageView) view.findViewById(R.id.thumbnail_delete_screen);

		Log.i(TAG,"thumb,getMeasuredWidth="+getMeasuredWidth()+",getMeasuredHeight="+getMeasuredHeight());
		Log.i(TAG,"thumb,getLeft="+getLeft()+",getRight="+getRight()+",getTop="+getTop()+",getBottom="+getBottom());
		
		mThumbWidth = (getMeasuredWidth() - mWidthStartPadding - mWidthEndPadding) / COL_NUM;
		mThumbHeight = (getMeasuredHeight() - mHeightStartPadding - mHeightEndPadding) / ROW_NUM;

		if (thumb != null && cell != null) {
			// Force set transformation to false
			// for drawing cell layout without any transformations
			cell.setStaticTransformationsEnabled(false);
			thumb.setImageBitmap(getThumbnail(cell));
			cell.setStaticTransformationsEnabled(true);
		}

		view.setOnClickListener(mThumbClickListener);

		// Set mCurrentScreen as focus
		view.setSelected((index == /*cell.getPageIndex()*/mWorkspace.mCurrentScreen));

		// Set mHomeScreenIndex indicator
		home.setOnClickListener(mHomeClickListener);
		if (/*index*/cell.getPageIndex() == SettingUtils.mHomeScreenIndex) {
			home.setImageResource(R.drawable.ic_homescreen_on);
		}

		delete.setOnClickListener(mDeleteScreenClickListener);
		view.setOnLongClickListener(mLongClickListener);
		return view;
	}

	private Bitmap getThumbnail(View view) {
		float scaleWidth = ((float) mThumbWidth) / ((float) view.getWidth());
		float scaleHeight = ((float) mThumbHeight) / ((float) view.getHeight());
		Bitmap bitmap = Bitmap.createBitmap(mThumbWidth, mThumbHeight, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(bitmap);

		c.scale(scaleWidth, scaleHeight);
		view.draw(c);

		return bitmap;
	}

	private int getXPos(int index, int thumbWidth) {
		if (index >= MAX_COUNT) {
			// Only support max_count screens
			return 0;
		}
		return thumbWidth * (index % COL_NUM) + mWidthStartPadding;
	}

	private int getYPos(int index, int thumbHeight) {
		if (index >= MAX_COUNT) {
			// Only support max_count screens
			return 0;
		}
		return thumbHeight * (index / ROW_NUM) + mHeightStartPadding;
	}

	@Override
	protected void onAnimationEnd() {
		// TODO Auto-generated method stub
		if (mIsVisible) {
			mLauncher.thumbnailShowed(true);
		} else {
			setVisibility(View.GONE);
			mLauncher.thumbnailShowed(false);
		}
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent event) {
		// TODO Auto-generated method stub
		final int action = event.getAction();

		switch (action) {
		case MotionEvent.ACTION_DOWN:
			mPos[0] = (int) event.getX();
			mPos[1] = (int) event.getY();
			break;
		case MotionEvent.ACTION_MOVE:
			if (mDragView != null) {
				mStartDrag = true;
				startDrag((int) event.getX(), (int) event.getY());
			}
			break;
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_CANCEL:
			mStartDrag = false;
			endDrag((int) event.getX(), (int) event.getY());
			break;
		}

		return super.onInterceptTouchEvent(event);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		// TODO Auto-generated method stub
		final int thumbWidth = mThumbWidth;
		final int thumbHeight = mThumbHeight;

		if(mDragView == null){
			for (int i = 0; i < getChildCount(); i++) {
				View view = getChildAt(i);
				if (view != null && view.getVisibility() == View.VISIBLE) {
					int left = getXPos(i, thumbWidth);
					int top = getYPos(i, thumbHeight);
					view.layout(left, top, left + thumbWidth, top + thumbHeight);
				}
			}	
		}
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		// TODO Auto-generated method stub
		int widthSpecSize =  MeasureSpec.getSize(widthMeasureSpec);
		int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
		int heightSpecSize =  MeasureSpec.getSize(heightMeasureSpec);
		int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
		int mWidthGap = 0;
		int mHeightGap = 0;

		if (widthSpecMode == MeasureSpec.UNSPECIFIED || heightSpecMode == MeasureSpec.UNSPECIFIED) {
			throw new RuntimeException("Thumbnail cannot have UNSPECIFIED dimensions");
		}

		mWidthGap = (widthSpecSize - mWidthStartPadding - mWidthEndPadding) / COL_NUM;
		mHeightGap = (heightSpecSize - mHeightStartPadding - mHeightEndPadding) / ROW_NUM;

		int childWidthMeasureSpec =
				MeasureSpec.makeMeasureSpec(mWidthGap, MeasureSpec.EXACTLY);
		int childHeightMeasureSpec =
				MeasureSpec.makeMeasureSpec(mHeightGap, MeasureSpec.EXACTLY);

		for (int i = 0; i < getChildCount(); i++) {
			getChildAt(i).measure(childWidthMeasureSpec, childHeightMeasureSpec);
		}
		setMeasuredDimension(widthSpecSize, heightSpecSize);
	}

	public View pointToView(int x, int y) {
		if (getChildCount() > 1) {
			Rect frame = new Rect();

			for (int i = 0; i < this.getChildCount(); i++) {
				final View child = this.getChildAt(i);
				if (child.getVisibility() == View.VISIBLE) {
					child.getHitRect(frame);
					if (frame.contains(x, y)) {
						return child;
					}
				}
			}
		}
		return null;
	}

	private void startAnimate(){
		if (mFromPos < mToPos) {
			for (int init = mFromPos + 1; init < mToPos; init++) {
				View child = getChildAt(init - 1);
				int x1 = getXPos(init, mThumbWidth);
				int y1 = getYPos(init, mThumbHeight);
				int x2 = getXPos(init - 1, mThumbWidth);
				int y2 = getYPos(init - 1, mThumbHeight);

				ItemAnimate itemAnimate = new ItemAnimate(x1, x2, y1, y2, child);
				itemAnimate.setDuration(600);
				itemAnimate.setSquare(mThumbWidth, mThumbHeight);
				itemAnimate.start();
			}

			View child = getChildAt(mToPos - 1);
			int x1 = getXPos(mToPos, mThumbWidth);
			int y1 = getYPos(mToPos, mThumbHeight);
			int x2 = getXPos(mToPos - 1, mThumbWidth);
			int y2 = getYPos(mToPos - 1, mThumbHeight);
			mItemAnimate.stop();
			mItemAnimate.setAnimateTarget(x1, x2, y1, y2, child);
			mItemAnimate.setDuration(600);
			mItemAnimate.setSquare(mThumbWidth, mThumbHeight);
			mItemAnimate.start();
			Log.d(TAG, " mFromPos="+ mFromPos + " mToPos=" + mToPos );
			mFromPos = mToPos;
		} else {
			for (int init = mToPos; init < mFromPos - 1; init++) {
				View child = getChildAt(init);
				int x1 = getXPos(init, mThumbWidth);
				int y1 = getYPos(init, mThumbHeight);
				int x2 = getXPos(init + 1, mThumbWidth);
				int y2 = getYPos(init + 1, mThumbHeight);

				ItemAnimate itemAnimate = new ItemAnimate(x1, x2, y1, y2, child);
				itemAnimate.setDuration(600);
				itemAnimate.setSquare(mThumbWidth, mThumbHeight);
				itemAnimate.start();
			}

			View child = getChildAt(mFromPos - 1);
			int x1 = getXPos(mFromPos - 1, mThumbWidth);
			int y1 = getYPos(mFromPos - 1, mThumbHeight);
			int x2 = getXPos(mFromPos, mThumbWidth);
			int y2 = getYPos(mFromPos, mThumbHeight);

			mItemAnimate.stop();
			mItemAnimate.setAnimateTarget(x1, x2, y1, y2, child);
			mItemAnimate.setDuration(600);
			mItemAnimate.setSquare(mThumbWidth, mThumbHeight);
			mItemAnimate.start();
			Log.d(TAG, " mFromPos="+ mFromPos + " mToPos=" + mToPos );
			mFromPos = mToPos;
		}
	}

	private int getPos(int x, int y) {
		int row = y / mThumbHeight; 
		int col = x / mThumbWidth; 

		return row * COL_NUM + col;
	}

	private void startDrag(int x, int y) {
		View view = pointToView(x, y);
		if (mItemAnimate.animateEnd && view != null) {
			if (view != mDragView) {
				mToPos = findViewIndex(view, 0);
				if (mToPos >= mFromPos) {
					mToPos++;
				}
				startAnimate();	
			} else if (view == mDragView) {
				int count = getChildCount();
				int pos = getPos(x, y);
				if (pos > count - 1 && mToPos != count - 1) {
					mToPos = count - 1;
					startAnimate();	
				}
			}
		}

		mDragView.layout(getPosX(x), getPosY(y),
				getPosX(x) + mThumbWidth, getPosY(y) + mThumbHeight);
	}

	private void endDrag(int x, int y) {
		if (mDragView != null) {
			if (mDragInitPos != mToPos) {
				mWorkspace.exchangeScreen(mDragInitPos, mToPos);
				mCurSelectedScreenIndex = mToPos;
				setFocusScreen(mCurSelectedScreenIndex);
			}
			removeViewAt(getChildCount() - 1);
			addView(mDragView, mToPos);
			mDragView = null;
			if (!mReachMax) {
				addView(mAddScreen);
			}
			invalidate();
		}
	}

	/**
	 * @return the mCurSelectedScreenIndex
	 */
	public int getmCurSelectedScreenIndex() {
		return mCurSelectedScreenIndex;
	}

	/**
	 * @param mCurSelectedScreenIndex the mCurSelectedScreenIndex to set
	 */
	public void setmCurSelectedScreenIndex(int mCurSelectedScreenIndex) {
		this.mCurSelectedScreenIndex = mCurSelectedScreenIndex;
	}
}