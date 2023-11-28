package org.springframework;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.port.A;

public class Main {
	public static void main(String[] args) {
		System.out.println("Hello world!");

		ApplicationContext context = new ClassPathXmlApplicationContext("applicationContext.xml");
		A a = (A) context.getBean("A");
		a.show();

	}
}