package org.springframework.port;

/**
 * @projectName: spring
 * @package: org.springframework.port
 * @className: A
 * @author: Eric
 * @description: TODO
 * @date: 2023/11/26 19:55
 * @version: 1.0
 */

public class A {

	private int id;

	public int getId() {
		return id;
	}

	public A setId(int id) {
		this.id = id;
		return this;
	}

	public String getName() {
		return name;
	}

	public A setName(String name) {
		this.name = name;
		return this;
	}

	private String name;

	public void show(){
		System.out.println("A.Show()");
	}
}
