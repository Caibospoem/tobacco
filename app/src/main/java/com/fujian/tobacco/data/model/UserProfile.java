package com.fujian.tobacco.data.model;

import java.io.Serializable;

/**
 * 用户信息（从平台获取）
 */
public class UserProfile implements Serializable {

    private String storeName;        // 商店名称
    private String licenseNo;        // 烟草专卖许可证号
    private String ownerName;        // 经营者姓名
    private int tierLevel;           // 挡位（1-30档）
    private String region;           // 所在区域，如"福州市鼓楼区"
    private String phoneNumber;      // 联系电话

    public UserProfile() {}

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }

    public String getLicenseNo() { return licenseNo; }
    public void setLicenseNo(String licenseNo) { this.licenseNo = licenseNo; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public int getTierLevel() { return tierLevel; }
    public void setTierLevel(int tierLevel) { this.tierLevel = tierLevel; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    @Override
    public String toString() {
        return storeName + " | " + tierLevel + "档 | " + region;
    }
}
