package org.springframework.selfEditor;

/**
 * @projectName: spring
 * @package: org.springframework.selfEditor
 * @className: Customer
 * @author: Eric
 * @description: TODO
 * @date: 2023/11/30 11:57
 * @version: 1.0
 */
public class Customer {

	private Address address;
	private String id;

	public Address getAddress() {
		return address;
	}

	public Customer setAddress(Address address) {
		this.address = address;
		return this;
	}

	public String getId() {
		return id;
	}

	public Customer setId(String id) {
		this.id = id;
		return this;
	}

	@Override
	public String toString() {
		return "Customer{" +
				"address=" + address +
				", id='" + id + '\'' +
				'}';
	}
}
