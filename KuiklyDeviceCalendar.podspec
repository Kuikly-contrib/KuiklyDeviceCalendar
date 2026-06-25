Pod::Spec.new do |spec|
  spec.name             = 'KuiklyDeviceCalendar'
  spec.version          = ENV['kuiklyBizVersion'] || '0.0.1'
  spec.summary          = 'KuiklyDeviceCalendar iOS SDK'
  spec.description      = <<-DESC
  KuiklyDeviceCalendar is an iOS SDK that provides device-calendar related capabilities for Kuikly cross-platform applications.
  DESC
  spec.homepage         = 'https://kuikly.tencent.com'
  spec.license          = { :type => 'MIT' }
  spec.author           = { 'Kuikly' => 'kuikly@tencent.com' }
  spec.source           = { :http => '' }
  spec.ios.deployment_target = '14.1'

  spec.requires_arc     = true
  spec.frameworks       = 'Foundation', 'UIKit', 'EventKit', 'EventKitUI'

  spec.source_files     = 'KuiklyDeviceCalendarIOS/**/*.{h,m}'
  spec.public_header_files = 'KuiklyDeviceCalendarIOS/**/*.h'

  spec.static_framework = true

  # 依赖 Kuikly iOS Render（提供 KRBaseModule、NSObject+KR 等）
  spec.dependency 'OpenKuiklyIOSRender'
end
