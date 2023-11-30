package org.springframework;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.port.A;
import org.springframework.selftag.User;

public class Main {
	public static void main(String[] args) {
		System.out.println("Hello world!");

		ApplicationContext context = new ClassPathXmlApplicationContext("applicationContext.xml");
		A a = (A) context.getBean("A");
		User user =(User) context.getBean("gjq");
		System.out.println(user);
		a.show();

	}


	/**
	*  如果需要自定义标签的话,应该做以下步骤
	* 1.创建一个对应的解析器处理类 (重写init方法 添加parser类)
	 * 2.创建一个普通的spring.handlers配置文件,让应用程序能够完成加载工作
	 * 3.创建对应标签的parser类
	* */
}