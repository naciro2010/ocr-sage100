package com.madaef.recondoc.config

import com.madaef.recondoc.entity.AppUser
import com.madaef.recondoc.repository.UserRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun userDetailsService(userRepo: UserRepository): UserDetailsService = UserDetailsService { email ->
        val user = userRepo.findByEmail(email)
            ?: throw UsernameNotFoundException("User not found: $email")
        if (!user.actif) throw UsernameNotFoundException("User disabled: $email")
        User(user.email, user.password, listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")))
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/actuator/**").permitAll()
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    // Static frontend files
                    .requestMatchers("/", "/index.html", "/assets/**", "/*.js", "/*.css", "/*.ico").permitAll()
                    // API requires authentication
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll()
            }
            .httpBasic { }
            .formLogin { it.disable() }
            .logout { it.logoutUrl("/api/auth/logout").logoutSuccessHandler { _, res, _ -> res.status = 200 } }

        return http.build()
    }

    @Bean
    fun createDefaultAdmin(userRepo: UserRepository, encoder: PasswordEncoder) = CommandLineRunner {
        if (!userRepo.existsByEmail("admin@madaef.ma")) {
            userRepo.save(AppUser(
                email = "admin@madaef.ma",
                password = encoder.encode("admin123"),
                nom = "Administrateur",
                role = com.madaef.recondoc.entity.UserRole.ADMIN
            ))
        }
    }
}
