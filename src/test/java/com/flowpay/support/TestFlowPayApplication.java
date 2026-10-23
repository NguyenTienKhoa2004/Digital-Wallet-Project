package com.flowpay.support;

import com.flowpay.FlowPayApplication;
import org.springframework.boot.SpringApplication;

public class TestFlowPayApplication {

	public static void main(String[] args) {
		SpringApplication.from(FlowPayApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
