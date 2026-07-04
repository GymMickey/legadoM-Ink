package io.legado.app.ui.book.read.page.provider

import io.legado.app.R
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.api.PageFactory
import io.legado.app.ui.book.read.page.entities.TextPage
import splitties.init.appCtx

class TextPageFactory(dataSource: DataSource) : PageFactory<TextPage>(dataSource) {

    private val keepSwipeTip = appCtx.getString(R.string.keep_swipe_tip)
    private val lazyLoadingTip = appCtx.getString(R.string.next_page_lazy_loading)

    private fun lazyLoadingPage(title: String): TextPage {
        return TextPage(text = lazyLoadingTip, title = title).format()
    }

    override fun hasPrev(): Boolean = with(dataSource) {
        return hasPrevChapter() || pageIndex > 0
    }

    override fun hasNext(): Boolean = with(dataSource) {
        val chapter = currentChapter ?: return@with hasNextChapter()
        if (pageIndex >= 0 && pageIndex < chapter.pageSize - 1) {
            return@with true
        }
        hasNextChapter()
    }

    override fun hasNextPlus(): Boolean = with(dataSource) {
        val chapter = currentChapter ?: return@with hasNextChapter()
        if (pageIndex >= 0 && pageIndex < chapter.pageSize - 2) {
            return@with true
        }
        hasNextChapter()
    }

    override fun moveToFirst() {
        ReadBook.setPageIndex(0)
    }

    override fun moveToLast() = with(dataSource) {
        currentChapter?.let {
            if (it.pageSize == 0) {
                ReadBook.setPageIndex(0)
            } else {
                ReadBook.setPageIndex(it.pageSize.minus(1))
            }
        } ?: ReadBook.setPageIndex(0)
    }

    override fun moveToNext(upContent: Boolean): Boolean = with(dataSource) {
        return if (hasNext()) {
            val chapter = currentChapter
            val pageIndex = pageIndex
            if (chapter == null || pageIndex >= chapter.pageSize - 1) {
                if (chapter == null && nextChapter == null) {
                    return@with false
                }
                ReadBook.moveToNextChapter(upContent, false)
            } else {
                if (pageIndex < 0) {
                    return@with false
                }
                ReadBook.setPageIndex(pageIndex.plus(1))
            }
            if (upContent) upContent(resetPageOffset = false)
            true
        } else
            false
    }

    override fun moveToPrev(upContent: Boolean): Boolean = with(dataSource) {
        return if (hasPrev()) {
            if (pageIndex <= 0) {
                if (currentChapter == null && prevChapter == null) {
                    return@with false
                }
                if (prevChapter != null && prevChapter?.isCompleted == false) {
                    return@with false
                }
                ReadBook.moveToPrevChapter(upContent, upContentInPlace = false)
            } else {
                if (currentChapter == null) {
                    return@with false
                }
                ReadBook.setPageIndex(pageIndex.minus(1))
            }
            if (upContent) upContent(resetPageOffset = false)
            true
        } else
            false
    }

    override val curPage: TextPage
        get() = with(dataSource) {
            ReadBook.msg?.let {
                return@with TextPage(text = it).format()
            }
            currentChapter?.let {
                return@with it.getPage(pageIndex)
                    ?: TextPage(title = it.title).apply { textChapter = it }.format()
            }
            return TextPage().format()
        }

    override val nextPage: TextPage
        get() = with(dataSource) {
            ReadBook.msg?.let {
                return@with TextPage(text = it).format()
            }
            currentChapter?.let {
                val pageIndex = pageIndex
                if (pageIndex < it.pageSize - 1) {
                    return@with it.getPage(pageIndex + 1)?.removePageAloudSpan()
                        ?: TextPage(title = it.title).format()
                }
                if (it.useLazyLoading && it.lazyContent?.isAnyPageLoading() == true) {
                    return@with lazyLoadingPage(it.title)
                }
                if (it.isFullyLoaded()) {
                    nextChapter?.let { next ->
                        return@with next.getPage(0)?.removePageAloudSpan()
                            ?: TextPage(title = next.title).format()
                    }
                }
                return@with TextPage(title = it.title).format()
            }
            return TextPage().format()
        }

    override val prevPage: TextPage
        get() = with(dataSource) {
            ReadBook.msg?.let {
                return@with TextPage(text = it).format()
            }
            currentChapter?.let {
                val pageIndex = pageIndex
                if (pageIndex > 0) {
                    return@with it.getPage(pageIndex - 1)?.removePageAloudSpan()
                        ?: TextPage(title = it.title).format()
                }
                if (!it.isCompleted) {
                    return@with TextPage(title = it.title).format()
                }
            }
            prevChapter?.let {
                return@with it.lastPage?.removePageAloudSpan()
                    ?: TextPage(title = it.title).format()
            }
            return TextPage().format()
        }

    override val nextPlusPage: TextPage
        get() = with(dataSource) {
            currentChapter?.let {
                val pageIndex = pageIndex
                if (pageIndex < it.pageSize - 2) {
                    return@with it.getPage(pageIndex + 2)?.removePageAloudSpan()
                        ?: TextPage(title = it.title).format()
                }
                if (it.useLazyLoading && it.lazyContent?.isAnyPageLoading() == true) {
                    return@with lazyLoadingPage(it.title)
                }
                if (it.isFullyLoaded()) {
                    nextChapter?.let { nc ->
                        if (pageIndex < it.pageSize - 1) {
                            return@with nc.getPage(0)?.removePageAloudSpan()
                                ?: TextPage(title = nc.title).format()
                        }
                        return@with nc.getPage(1)?.removePageAloudSpan()
                            ?: TextPage(text = keepSwipeTip).format()
                    }
                }
                return@with TextPage(title = it.title).format()
            }
            return TextPage().format()
        }
}
