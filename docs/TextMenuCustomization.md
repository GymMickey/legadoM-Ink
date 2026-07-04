# 文本菜单项自定义功能使用说明

## 功能概述

该功能允许用户自定义文本操作菜单中显示哪些菜单项，隐藏哪些菜单项。

## 菜单项列表

| 菜单项 | ID | 说明 |
|--------|-----|------|
| 替换 | R.id.menu_replace | 替换文本内容 |
| 复制 | R.id.menu_copy | 复制到剪贴板 |
| 书签 | R.id.menu_bookmark | 添加书签 |
| 朗读 | R.id.menu_aloud | 朗读选中文本 |
| 字典 | R.id.menu_dict | 查字典 |
| 搜索内容 | R.id.menu_search_content | 在本地内容中搜索 |
| 浏览器 | R.id.menu_browser | 用浏览器打开或搜索 |
| 分享 | R.id.menu_share_str | 分享文本 |

## 使用方法

### 1. 在设置界面添加菜单项配置

```kotlin
// 在设置界面中添加菜单项配置列表
class TextMenuSettingsFragment : Fragment() {
    
    private lateinit var binding: FragmentTextMenuSettingsBinding
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentTextMenuSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 获取所有菜单项
        val menuItems = TextMenuConfig.getAllMenuItems()
        
        // 获取当前隐藏的菜单项
        val hiddenIds = TextMenuConfig.getHiddenMenuItemIds(requireContext())
        
        // 创建菜单项列表适配器
        val adapter = MenuItemsAdapter(menuItems, hiddenIds) { itemId ->
            // 切换菜单项显示状态
            val isVisible = TextMenuConfig.toggleMenuItem(requireContext(), itemId)
            // 更新UI
            updateMenuItemStatus(itemId, isVisible)
        }
        
        binding.recyclerView.adapter = adapter
    }
}
```

### 2. 简单的切换示例

```kotlin
// 隐藏"字典"菜单项
TextMenuConfig.toggleMenuItem(context, R.id.menu_dict)

// 隐藏"搜索内容"菜单项
TextMenuConfig.toggleMenuItem(context, R.id.menu_search_content)

// 重置为默认配置（所有菜单项都显示）
TextMenuConfig.resetToDefault(context)
```

### 3. 批量设置隐藏的菜单项

```kotlin
// 隐藏多个菜单项
val hiddenIds = setOf(
    R.id.menu_dict,
    R.id.menu_search_content,
    R.id.menu_browser
)
TextMenuConfig.setHiddenMenuItemIds(context, hiddenIds)
```

### 4. 检查菜单项是否被隐藏

```kotlin
// 检查"字典"菜单项是否被隐藏
val isHidden = TextMenuConfig.isMenuItemHidden(context, R.id.menu_dict)
if (isHidden) {
    // 菜单项被隐藏
} else {
    // 菜单项可见
}
```

## 实现原理

1. **配置存储**：使用SharedPreferences存储隐藏的菜单项ID列表（逗号分隔的字符串）

2. **菜单过滤**：在TextActionMenu初始化时，从配置中读取隐藏的菜单项ID，过滤掉这些菜单项

3. **动态更新**：修改配置后，下次打开菜单时会自动应用新的配置

## 注意事项

1. 菜单项的显示顺序由`TextMenuConfig.ALL_MENU_ITEMS`列表的顺序决定

2. 如果隐藏的菜单项过多，导致可见菜单项少于5个，则不会显示"更多"按钮

3. 系统文本处理菜单（Android 6.0+）不受此配置影响，始终显示

4. 修改配置后需要重新打开菜单才能看到效果

## 扩展建议

1. 可以添加拖拽排序功能，让用户自定义菜单项的显示顺序

2. 可以添加"恢复默认"按钮，一键恢复所有菜单项的显示

3. 可以添加菜单项使用频率统计，自动调整菜单项顺序

4. 可以支持菜单项分组，让用户按组管理菜单项
