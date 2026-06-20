package com.fujian.tobacco.data.model;

import java.io.Serializable;

/**
 * 单条卷烟投放策略
 *
 * 对应 Excel "档级投放" Sheet 中的一行数据
 */
public class SupplyItem implements Serializable {

    // 基本信息
    private String brandName;        // 卷烟品牌名称，如"利群(休闲金中支)"
    private String productCode;      // 商品代码，如"13320189"

    // 分类
    private String tierCategory;     // 品类（一档~八档）
    private String region;           // 区域（全区）
    private String supplyNote;       // 备注（投放/不投/新品）

    // 数量
    private int totalRetailers;      // 投放户数
    private int personalQuota;       // 当前挡位的个人配额（条）
    private int userTier;            // 用户挡位

    // 价格（Excel中可能不直接有，后续可从其他数据源补充）
    private double tradePrice;       // 批发价（元/条）
    private double retailPrice;      // 建议零售价（元/条）

    public SupplyItem() {}

    // ===== Getters =====
    public String getBrandName() { return brandName; }
    public String getProductCode() { return productCode; }
    public String getTierCategory() { return tierCategory; }
    public String getRegion() { return region; }
    public String getSupplyNote() { return supplyNote; }
    public int getTotalRetailers() { return totalRetailers; }
    public int getPersonalQuota() { return personalQuota; }
    public int getUserTier() { return userTier; }
    public double getTradePrice() { return tradePrice; }
    public double getRetailPrice() { return retailPrice; }

    // ===== Setters =====
    public void setBrandName(String s) { this.brandName = s; }
    public void setProductCode(String s) { this.productCode = s; }
    public void setTierCategory(String s) { this.tierCategory = s; }
    public void setRegion(String s) { this.region = s; }
    public void setSupplyNote(String s) { this.supplyNote = s; }
    public void setTotalRetailers(int n) { this.totalRetailers = n; }
    public void setPersonalQuota(int n) { this.personalQuota = n; }
    public void setUserTier(int n) { this.userTier = n; }
    public void setTradePrice(double v) { this.tradePrice = v; }
    public void setRetailPrice(double v) { this.retailPrice = v; }

    // ===== 兼容旧字段别名 =====
    public void setCategory(String s) { this.tierCategory = s; }
    public String getCategory() { return tierCategory; }
    public void setManufacturer(String s) { this.region = s; }
    public String getManufacturer() { return region; }
    public void setSupplyType(String s) { this.supplyNote = s; }
    public String getSupplyType() { return supplyNote; }
    public void setTotalSupply(int n) { this.totalRetailers = n; }
    public int getTotalSupply() { return totalRetailers; }
    public void setTierLevel(int n) { this.userTier = n; }
    public int getTierLevel() { return userTier; }
    public String getSpecification() { return ""; }
    public String getPriceRange() { return ""; }

    @Override
    public String toString() {
        return brandName + " | " + tierCategory
                + " | 配额:" + personalQuota + "条"
                + (supplyNote != null ? " | " + supplyNote : "");
    }
}
