package com.taskforge.security;

import java.util.List;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.taskforge.common.EmailNormalizer;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

@Service
public class CustomUserDetailsService implements UserDetailsService {

	private final UserRepository userRepository;

	public CustomUserDetailsService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		User user = userRepository.findByEmail(EmailNormalizer.normalize(email))
				.orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));

		return new org.springframework.security.core.userdetails.User(
				user.getEmail(), user.getPasswordHash(), List.of());
	}

}
