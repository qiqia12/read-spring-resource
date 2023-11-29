package org.springframework.selftag;

/**
 * @projectName: spring
 * @package: org.springframework.selftag
 * @className: User
 * @author: Eric
 * @description: TODO 实体类用标签语言定义的实体类 必须存在 id属性
 * @date: 2023/11/29 18:29
 * @version: 1.0
 */
public class User {
	private String username;
	private String email;

	@Override
	public String toString() {
		return "User{" +
				"username='" + username + '\'' +
				", email='" + email + '\'' +
				", age=" + age +
				", password='" + password + '\'' +
				'}';
	}

	private int age;
	private String id;
	private String password;

	public String getUsername() {
		return username;
	}

	public User setUsername(String username) {
		this.username = username;
		return this;
	}

	public String getEmail() {
		return email;
	}

	public User setEmail(String email) {
		this.email = email;
		return this;
	}

	public int getAge() {
		return age;
	}

	public String getId() {
		return id;
	}

	public User setId(String id) {
		this.id = id;
		return this;
	}

	public User setAge(int age) {
		this.age = age;
		return this;
	}

	public String getPassword() {
		return password;
	}

	public User setPassword(String password) {
		this.password = password;
		return this;
	}
}
