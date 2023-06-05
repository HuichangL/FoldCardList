package com.example.layoutmanager

import android.util.TypedValue
import android.animation.Animator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import java.lang.Exception
import kotlin.math.abs
import kotlin.math.floor

class FoldLinearLayoutManager @JvmOverloads constructor(
    context: Context,
    private var itemSpace: Int = 40,
) : RecyclerView.LayoutManager() {

    companion object {
        fun dp2px(context: Context, dp: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                context.resources.displayMetrics
            ).toInt()
        }
    }

    /**
     * 最小滑动距离，子View宽度+itemSpace
     */
    private var minCompleteScrollDistance = -1

    /**
     * 每次滑动的偏移碎片
     */
    private var mFraction: Float = 0f

    /**
     * 每次滑动的偏移碎片填充View的最左起点
     */
    private var mFillStartX = 0

    /**
     * 屏幕可见第一个view的position
     */
    private var mFirstVisiPos = 0

    /**
     * 屏幕可见的最后一个view的position
     */
    private var mLastVisiPos = 0

    /**
     * 水平方向累计偏移量
     */
    private var mHorizontalOffset: Long = 0

    /**
     * 子view的宽度
     */
    private var childWidth = 0

    private var selectAnimator: ValueAnimator? = null
    private var mPendingPosition = RecyclerView.NO_POSITION


    init {
        itemSpace = dp2px(context, itemSpace.toFloat())
    }

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        try {
            if (state.itemCount <= 0) {
                removeAndRecycleAllViews(recycler)
                return
            }
            if (mFirstVisiPos >= state.itemCount) {
                reset()
            }
            minCompleteScrollDistance = -1

            // 轻量级回收View
            detachAndScrapAttachedViews(recycler)
            fill(recycler, state, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun reset() {
        mFirstVisiPos = 0
        mLastVisiPos = 0
        mHorizontalOffset = 0
        minCompleteScrollDistance = -1
        childWidth = 0
    }

    private fun fill(recycler: RecyclerView.Recycler, state: RecyclerView.State, dx: Int): Int {
        val resultDelta = fillHorizontal(recycler, state, dx)
        recycleChildren(recycler)
        return resultDelta
    }

    override fun canScrollHorizontally(): Boolean {
        return true
    }

    override fun canScrollVertically(): Boolean {
        return false
    }

    override fun scrollHorizontallyBy(
        delta: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        var dx = delta
        try {
            if (mPendingPosition != RecyclerView.NO_POSITION) {
                dx = offsetByScrollPosition
            }

            // 位移0、没有子View 不移动
            if (dx == 0 || childCount == 0) {
                return 0
            }

            // 手指从右向左滑动，dx > 0; 手指从左向右滑动，dx < 0;
            if (dx > 0 && mHorizontalOffset > maxOffset) {
                return 0
            }
            val realDx = dx / 1.0f
            if (abs(realDx) < 0.00000001f) {
                return 0
            }

            mHorizontalOffset += dx.toLong()
            dx = fill(recycler, state, dx)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return dx
    }

    /**
     * 最大偏移量
     *
     * @return
     */
    private val maxOffset: Float
        get() {
            if (childWidth == 0 || itemCount == 0) return 0f
            if (width > (childWidth + itemSpace) * itemCount) {
                return 0f
            }
            val remainSpace = width % (childWidth + itemSpace)
            return (childWidth + itemSpace).toFloat() * itemCount + remainSpace - width
        }

    /**
     * 获取最小的偏移量
     *
     * @return
     */
    private val minOffset: Int
        get() = if (childWidth == 0) 0 else paddingStart

    private val offsetByScrollPosition: Int
        get() {
            if (mPendingPosition != RecyclerView.NO_POSITION) {
                var dx = 0
                dx = if (mPendingPosition == 0) {
                    -mHorizontalOffset.toInt()
                } else {
                    val fraction =
                        ((mHorizontalOffset % childWidth).toInt() / (childWidth * 1.0f)).toInt()
                    -(mFirstVisiPos - mPendingPosition) * childWidth - fraction
                }
                return dx
            }
            return 0
        }

    private fun fillHorizontal(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        delta: Int
    ): Int {
        //边界检测
        var dx = boundedCheck(delta)

        // 分离全部的view，加入到临时缓存
        detachAndScrapAttachedViews(recycler)


        // 防止mFirstVisiPos >= state.itemCount导致crash
        if (mFirstVisiPos >= state.itemCount) {
            return delta
        }

        // 计算childWidth
        val tempPosition = mFirstVisiPos
        var tempView: View? = null
        if (minCompleteScrollDistance == -1) {
            // 该View仅用来计算childWidth
            tempView = recycler.getViewForPosition(tempPosition)
            measureChildWithMargins(tempView, 0, 0)
            childWidth = getDecoratedMeasurementHorizontal(tempView)
            minCompleteScrollDistance = childWidth + itemSpace
        }
        if (childWidth <= 0) {
            return delta
        }


        // 计算第一个可见View的位置
        findFirstPosition()
        
        // 临时将mLastVisiPos赋值为getItemCount() - 1，下面遍历时会判断view是否已溢出屏幕，并及时修正该值并结束布局
        mLastVisiPos = state.itemCount - 1
        val normalViewOffset = minCompleteScrollDistance * mFraction
        var isNormalViewOffsetSetted = false

        //----------------3、开始布局-----------------
        for (i in mFirstVisiPos..mLastVisiPos) {

            // 获取child
            var item: View = if (i == tempPosition && tempView != null) {
                // 如果初始化数据时已经取了一个临时view
                tempView
            } else {
                recycler.getViewForPosition(i)
            }

            // add child
            val focusPosition = (abs(mHorizontalOffset) / (childWidth + itemSpace)).toInt()
            if (i <= focusPosition) {
                addView(item)
            } else {
                addView(item, 1)
            }
            measureChildWithMargins(item, 0, 0)
            if (!isNormalViewOffsetSetted) {
                mFillStartX -= normalViewOffset.toInt()
                isNormalViewOffsetSetted = true
            }

            layoutChunk(item, i)

            if (mLastVisiPos == i) {
                break
            }
        }
        return dx
    }

    private fun boundedCheck(dx: Int): Int {
        if (dx < 0 && mHorizontalOffset < 0) {
            // 已到达左边界
            mHorizontalOffset = 0
            return 0
        } else if (mHorizontalOffset >= maxOffset) {
            // 到达右边界
            mHorizontalOffset = maxOffset.toLong()
            return 0
        }
        return dx
    }

    private fun findFirstPosition() {
        if (mPendingPosition != RecyclerView.NO_POSITION) {
            mFirstVisiPos = mPendingPosition
            return
        }
        if (childWidth in 1..mHorizontalOffset) {
            mFillStartX = paddingStart + itemSpace
            minCompleteScrollDistance = childWidth + itemSpace
            mFirstVisiPos =
                floor((abs(mHorizontalOffset - childWidth) / minCompleteScrollDistance).toDouble())
                    .toInt() + 1
            mFraction =
                abs(mHorizontalOffset - childWidth) % minCompleteScrollDistance / (minCompleteScrollDistance * 1.0f)
        } else {
            mFirstVisiPos = 0
            mFillStartX = minOffset
            minCompleteScrollDistance = childWidth
            mFraction =
                abs(mHorizontalOffset) % minCompleteScrollDistance / (minCompleteScrollDistance * 1.0f)
        }

    }

    private fun layoutChunk(view: View, position: Int) {
        var left = mFillStartX
        var top: Int = paddingTop
        var right = left + getDecoratedMeasurementHorizontal(view)
        var bottom: Int = top + getDecoratedMeasurementVertical(view)

        // 缩放子view
        val minScale = 0.3f
        var currentScale = 0f
        // 最左侧卡片隐藏时需要缩放
        if (position == mFirstVisiPos && left < paddingStart) {
            val scale = minScale * abs(left - paddingStart) / (childWidth * 1.0f)
            currentScale = 1 - scale
            left = (left + (itemSpace / 2 + childWidth / 2) * scale / minScale).toInt()
            right = left + getDecoratedMeasurementHorizontal(view)
        } else {
            // 不折叠Footer
            if (right < width) {
                currentScale = 1.0f
                view.alpha = 1.0f
            } else {
                val fractionScale = minScale * abs(right - width) / (childWidth * 1.0f)
                currentScale = 1 - fractionScale
                left -= abs(right - width)
                right = left + getDecoratedMeasurementHorizontal(view)
                view.pivotX = if(view.width == 0) getDecoratedMeasuredWidth(view).toFloat() else view.width.toFloat()
                view.pivotY = if (view.height == 0) (getDecoratedMeasuredHeight(view) / 2).toFloat() else (view.height / 2).toFloat()
                view.alpha = 0.6f
            }
        }
        view.scaleX = currentScale
        view.scaleY = currentScale

        layoutDecoratedWithMargins(view, left, top, right, bottom)
        mFillStartX += childWidth + itemSpace
        if (mFillStartX > width - paddingRight) {
            mLastVisiPos = position
        }
    }

    override fun onLayoutCompleted(state: RecyclerView.State) {
        mPendingPosition = RecyclerView.NO_POSITION
    }

    override fun isAutoMeasureEnabled(): Boolean {
        return true
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        when (state) {
            RecyclerView.SCROLL_STATE_DRAGGING ->                 //当手指按下时，停止当前正在播放的动画
                cancelAnimator()
            RecyclerView.SCROLL_STATE_IDLE -> {
                //找到离目标落点最近的item索引
                smoothScrollToPosition(findShouldSelectPosition(), null)
            }
            else -> {}
        }
    }

    private fun findShouldSelectPosition(): Int {
        if (minCompleteScrollDistance == -1 || mFirstVisiPos == -1) {
            return -1
        }
        val position = (abs(mHorizontalOffset) / (childWidth + itemSpace)).toInt()
        val remainder = (abs(mHorizontalOffset) % (childWidth + itemSpace)).toInt()
        // 超过一半，应当选中下一项
        if (remainder >= (childWidth + itemSpace) / 2.0f) {
            if (position + 1 <= itemCount - 1) {
                return position + 1
            }
        }
        return position
    }

    /**
     * 平滑滚动到某个位置
     *
     * @param position 目标Item索引
     */
    fun smoothScrollToPosition(position: Int, listener: OnStackListener?, duration: Long? = null) {
        if (position > -1 && position < itemCount) {
            startValueAnimator(position, listener, duration)
        }
    }

    override fun startSmoothScroll(smoothScroller: RecyclerView.SmoothScroller?) {
        smoothScroller?.let {
            smoothScrollToPosition(it.targetPosition, null)
        }
    }

    fun smoothScrollToFirst() {
        mHorizontalOffset = maxOffset.toLong().coerceAtMost(1200L)
        smoothScrollToPosition(0, null, 300L)
    }

    private fun startValueAnimator(position: Int, listener: OnStackListener?, durationDesired: Long?= null) {
        cancelAnimator()
        val distance = getScrollToPositionOffset(position)
        val minDuration: Long = 200
        val maxDuration: Long = 600
        val duration: Long = if (durationDesired != null) {
            durationDesired
        } else {
            val distanceFraction = Math.abs(distance) / (childWidth + itemSpace)
            if (distance <= childWidth + itemSpace) {
                (minDuration + (maxDuration - minDuration) * distanceFraction).toLong()
            } else {
                (maxDuration * distanceFraction).toLong()
            }
        }
        selectAnimator = ValueAnimator.ofFloat(0.0f, distance)
        selectAnimator?.duration = duration
        selectAnimator?.interpolator = LinearInterpolator()
        val startedOffset = mHorizontalOffset.toFloat()
        selectAnimator?.addUpdateListener(AnimatorUpdateListener { animation ->
            val value = animation.animatedValue as Float
            mHorizontalOffset = (startedOffset + value).toLong()
            requestLayout()
        })
        selectAnimator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                listener?.onFocusAnimEnd()
            }
        })
        selectAnimator?.start()
    }

    override fun scrollToPosition(position: Int) {
        if (position < 0 || position >= itemCount) return
        cancelAnimator()
        mPendingPosition = position
        requestLayout()
    }

    /**
     * @param position
     * @return
     */
    private fun getScrollToPositionOffset(position: Int): Float {
        if (position * (childWidth + itemSpace) > maxOffset) {
            return maxOffset - mHorizontalOffset
        }
        return position * (childWidth + itemSpace).toFloat() - mHorizontalOffset
    }

    /**
     * 取消动画
     */
    private fun cancelAnimator() {
        if (selectAnimator != null && (selectAnimator!!.isStarted || selectAnimator!!.isRunning)) {
            selectAnimator!!.cancel()
        }
    }

    fun canScrollHorizontally(direction: Int): Boolean {
        return if (direction < 0) {
            mHorizontalOffset > 0
        } else {
            mHorizontalOffset < maxOffset
        }
    }

    /**
     * 回收需回收的item
     */
    private fun recycleChildren(recycler: RecyclerView.Recycler) {
        val scrapList = recycler.scrapList
        for (i in scrapList.indices) {
            val holder = scrapList[i]
            removeAndRecycleView(holder.itemView, recycler)
        }
    }

    /**
     * 获取某个childView在水平方向所占的空间，将margin考虑进去
     *
     * @param view
     * @return
     */
    fun getDecoratedMeasurementHorizontal(view: View): Int {
        val params = view.layoutParams as RecyclerView.LayoutParams
        return (getDecoratedMeasuredWidth(view) + params.leftMargin
                + params.rightMargin)
    }

    /**
     * 获取某个childView在竖直方向所占的空间,将margin考虑进去
     *
     * @param view
     * @return
     */
    private fun getDecoratedMeasurementVertical(view: View): Int {
        val params = view.layoutParams as RecyclerView.LayoutParams
        return (getDecoratedMeasuredHeight(view) + params.topMargin
                + params.bottomMargin)
    }

    val verticalSpace: Int
        get() = height - paddingTop - paddingBottom

    val horizontalSpace: Int
        get() = width - paddingLeft - paddingRight

    override fun onAdapterChanged(
        oldAdapter: RecyclerView.Adapter<*>?,
        newAdapter: RecyclerView.Adapter<*>?
    ) {
        super.onAdapterChanged(oldAdapter, newAdapter)
        removeAllViews()
    }

    interface OnStackListener {
        fun onFocusAnimEnd()
    }
}