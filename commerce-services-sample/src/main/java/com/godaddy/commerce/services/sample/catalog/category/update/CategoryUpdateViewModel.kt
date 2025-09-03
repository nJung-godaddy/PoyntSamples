@file:OptIn(FlowPreview::class)

package com.godaddy.commerce.services.sample.catalog.category.update

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.godaddy.commerce.catalog.model.CatalogCategoryTreeNode
import com.godaddy.commerce.catalog.model.CatalogProduct
import com.godaddy.commerce.common.DataSource
import com.godaddy.commerce.provider.catalog.CatalogContract
import com.godaddy.commerce.sdk.catalog.CategoryParamsExt
import com.godaddy.commerce.sdk.catalog.ProductParamsExt
import com.godaddy.commerce.sdk.catalog.getCatalogCategoryTreeNode
import com.godaddy.commerce.services.sample.catalog.product.ProductRecyclerItem
import com.godaddy.commerce.services.sample.catalog.product.mapToCategoryUiItems
import com.godaddy.commerce.services.sample.common.viewmodel.CommonState
import com.godaddy.commerce.services.sample.common.viewmodel.CommonViewModel
import com.godaddy.commerce.services.sample.common.viewmodel.ToolbarState
import com.godaddy.commerce.services.sample.di.CommerceDependencyProvider.getCatalogService
import com.godaddy.commerce.sdk.catalog.getCatalogProducts
import com.godaddy.commerce.sdk.catalog.updateCatalogCategoryTreeNode
import com.godaddy.commercecore.models.Category
import com.godaddy.commercecore.models.CategoryProduct
import com.godaddy.commercecore.models.CategoryTreeNode
import kotlinx.coroutines.FlowPreview

class CategoryUpdateViewModel(
    private val savedStateHandle: SavedStateHandle
) : CommonViewModel<CategoryUpdateViewModel.State>(State()) {

    private val catalogServiceClient = getCatalogService(viewModelScope)
    private val id get() = savedStateHandle.get<String>("id")

    init {
        loadCategory()
        loadProducts()
    }

    private fun setCategoryState(response: CatalogCategoryTreeNode?) {
        update {
            val categoryTreeNode = response?.categoryTreeNode!!
            val category = categoryTreeNode.category!!
            val categoryProducts = category.products.orEmpty().associateBy { id!! }
            copy(
                updatedCatalogCategoryTreeNode = response,
                updatedCategoryTreeNode = categoryTreeNode,
                updatedCategory = category,
                updatedLabel = category.label,
                updatedDisplayOrder = categoryTreeNode.displayOrder,
                categoryProducts = categoryProducts
            )
        }
    }

    private fun loadCategory() {
        execute {
            val service = catalogServiceClient.getService().getOrThrow()
            val bundle = CategoryParamsExt.toBundle(
                includeProductIds = true
            )
            val response =  service.getCatalogCategoryTreeNode(id, bundle)
            setCategoryState(response)
        }
    }

    private fun loadProducts(query: String? = null) {
        execute {
            val service = catalogServiceClient.getService().getOrThrow()
            val bundle = ProductParamsExt.toBundle(
                dataSource = DataSource.REMOTE_IF_EMPTY,
                pageOffset = DEFAULT_CATEGORY_PRODUCTS_PAGE_OFFSET,
                pageSize = DEFAULT_CATEGORY_PRODUCTS_PAGE_SIZE,
                sortBy = CatalogContract.Product.Columns.UPDATED_AT,
                searchTerm = query,
            )
            val response = service.getCatalogProducts(bundle)
            val productMap = response?.products.orEmpty()
                .associateBy { requireNotNull(it.product.id) }
            createItems(productMap)
        }
    }

    private fun createItems(productMap: Map<String, CatalogProduct>) {
        val addedProducts = state.categoryProducts
        val otherProducts = productMap.minus(addedProducts.keys)
        val addedItems = addedProducts.keys.associateWith { id ->
            val product = productMap[id]!!
            product.mapToCategoryUiItems(
                isSelected = true,
                onDeleteClicked = {catalogProduct -> removeProduct(catalogProduct)},
                onSelectClicked = { _, _ ->}
            )
        }
        val otherItems = otherProducts.entries.associate {
            it.key to it.value.mapToCategoryUiItems(
                isSelected = false,
                onDeleteClicked = {catalogProduct -> removeProduct(catalogProduct)},
                onSelectClicked = { catalogProduct, _ -> selectProduct(catalogProduct)}
            )
        }
        update{ copy(
            addedItems = addedItems,
            items = otherItems
        )}
    }

    private fun selectProduct(catalogProduct: CatalogProduct) {
        val productId = catalogProduct.product.id!!
        if (state.categoryProducts.contains(productId)) { return }

        val productItem = state.items[productId]!!
        val nextDisplayOrder = state.categoryProducts.size + 1
        // @TODO: IF WE DO LINKED HASH IMPLEMENTATION, COULD APPEND TO END EASY
        //  AND LET USER ADJUST ORDER EASILY
        // CURRENT IMP HAS ISSUE WITH WHEN SIZE DECREASES AGIAN DUPLICATES CAN EXIST
        val addedCategoryProduct = Pair(productId, CategoryProduct(
            id = productId,
            displayOrder = nextDisplayOrder + 1
        ))
        update {
            copy(
                selectedProduct = catalogProduct,
                items = items.minus(productId),
                addedItems = addedItems.plus(Pair(productId, productItem)),
                categoryProducts = categoryProducts.plus(addedCategoryProduct)
            )
        }
    }

    private fun removeProduct(catalogProduct: CatalogProduct) {
        val productId = requireNotNull(catalogProduct.product.id)
        val productItem = state.addedItems[productId]!!
        update {
            copy(
                categoryProducts = categoryProducts.minus(productId),
                addedItems = addedItems.minus(productId),
                items = items.plus(Pair(productId, productItem))
            )
        }
    }

    fun onLabelUpdated(value: String) {
        update {
            copy(
                updatedLabel = value,
                updatedShortLabel = value.take(DEFAULT_CATEGORY_SHORT_LABEL_SIZE)
            )
        }
    }

    fun onDisplayOrderUpdated(value: String) {
        value.toIntOrNull()?.let {
            update { copy(updatedDisplayOrder = it) }
        }
    }

    fun updateCategory() {
        execute {
            val service = catalogServiceClient.getService().getOrThrow()
            val category = Category(
                id = state.updatedCategory?.id,
                label = state.updatedLabel,
                shortLabel = state.updatedShortLabel,
                displayOrder = state.updatedDisplayOrder,
                products = state.categoryProducts.values.toList(),
            )
            val categoryTreeNode = CategoryTreeNode(
                id = state.updatedCategoryTreeNode?.id,
                category = category,
                displayOrder = state.updatedDisplayOrder,
                categoryTreeId = state.updatedCategoryTreeNode?.categoryTreeId,
                parentNodeId = state.updatedCategoryTreeNode?.parentNodeId,
                childNodeIds = state.updatedCategoryTreeNode?.childNodeIds
            )
            val request = CatalogCategoryTreeNode(
                categoryTreeNode = categoryTreeNode
            )

            val response = service.updateCatalogCategoryTreeNode(id.orEmpty(), request)
            setCategoryState(response)
            update { copy(updatedCategoryId = updatedCategory?.id) }
        }
    }
    data class State(
        override val commonState: CommonState = CommonState(),
        override val toolbarState: ToolbarState = ToolbarState(title = "Category Update"),

        val updatedCategoryId: String? = null,
        val updatedCatalogCategoryTreeNode: CatalogCategoryTreeNode? = null,
        val updatedCategoryTreeNode: CategoryTreeNode? = null,
        val updatedCategory: Category? = null,
        val updatedLabel: String? = null,
        val updatedShortLabel: String? = null,
        val updatedDisplayOrder: Int? = null,
        val categoryProducts: Map<String, CategoryProduct> = emptyMap(),
        val items: Map<String, ProductRecyclerItem> = emptyMap(),
        val addedItems:Map<String, ProductRecyclerItem> = emptyMap(),
        val selectedProductId: String? = null,

        val selectedProduct: CatalogProduct? = null,
    ) : ViewModelState

    companion object{
        private const val DEFAULT_CATEGORY_SHORT_LABEL_SIZE = 5
        private const val DEFAULT_CATEGORY_PRODUCTS_PAGE_SIZE = 100
        private const val DEFAULT_CATEGORY_PRODUCTS_PAGE_OFFSET = 0
    }
}