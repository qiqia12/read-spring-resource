package org.springframework.selfEditor;

/**
 * @projectName: spring
 * @package: org.springframework.selfEditor
 * @className: Address
 * @author: Eric
 * @description: TODO
 * @date: 2023/11/30 11:56
 * @version: 1.0
 */
public class Address {

	private String town;
	private String city;
	private String province;

	public String getTown() {
		return town;
	}

	public Address setTown(String town) {
		this.town = town;
		return this;
	}

	public String getCity() {
		return city;
	}

	public Address setCity(String city) {
		this.city = city;
		return this;
	}

	public String getProvince() {
		return province;
	}

	public Address setProvince(String province) {
		this.province = province;
		return this;
	}

	@Override
	public String toString() {
		return "Address{" +
				"town='" + town + '\'' +
				", city='" + city + '\'' +
				", province='" + province + '\'' +
				'}';
	}
}
