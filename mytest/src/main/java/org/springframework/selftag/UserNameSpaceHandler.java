package org.springframework.selftag;

import org.springframework.beans.factory.xml.NamespaceHandlerSupport;
import org.springframework.context.annotation.AnnotationConfigBeanDefinitionParser;
import org.springframework.context.annotation.ComponentScanBeanDefinitionParser;
import org.springframework.context.config.*;

/**
 * @projectName: spring
 * @package: org.springframework.selftag
 * @className: UserNameSpaceHandler
 * @author: Eric
 * @description: TODO 对自定义标签解析的解析处理类 需要继承NamespaceHandlerSupport类 并重写init方法 定义对某个标签的处理类
 * @date: 2023/11/29 18:40
 * @version: 1.0
 */
public class UserNameSpaceHandler extends NamespaceHandlerSupport {
	@Override
	public void init() {
		registerBeanDefinitionParser("user", new UserBeanDefinitionParser());
	}
}
