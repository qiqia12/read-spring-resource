package org.springframework.selftag;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.util.StringUtils;
import org.w3c.dom.Element;

/**
 * @projectName: spring
 * @package: org.springframework.selftag
 * @className: UserBeanDefinitionParser
 * @author: Eric
 * @description: TODO UserBean定义的解析类 需要继承AbstractSingleBeanDefinitionParser 或其父类 并重写 getBeanClass doParse 方法
 * @date: 2023/11/29 18:31
 * @version: 1.0
 */
public class UserBeanDefinitionParser extends AbstractSingleBeanDefinitionParser {

	//返回属性值所对应的对象
	@Override
	protected Class<?> getBeanClass(Element element) {


		return User.class;
	}

	@Override
	protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
		//获取标签 具备的属性值
		String username = element.getAttribute("username");
		String id = element.getAttribute("id");
		String email = element.getAttribute("email");
		String password = element.getAttribute("password");
		String age = element.getAttribute("age");
		if (StringUtils.hasText(username)){
			builder.addPropertyValue("username",username);
		}
		if (StringUtils.hasText(email)){
			builder.addPropertyValue("email",email);
		}
		if (StringUtils.hasText(password)){
			builder.addPropertyValue("password",password);
		}
		if (StringUtils.hasText(age)){
			builder.addPropertyValue("age",age);
		}
		if (StringUtils.hasText(id)){
			builder.addPropertyValue("id",id);
		}

	}
}
