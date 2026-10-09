import SwiftUI
import UIKit
import TechnicianApp

@main
struct TechnicianApplication: App {
    var body: some Scene {
        WindowGroup {
            TechnicianScreen().ignoresSafeArea()
        }
    }
}

private struct TechnicianScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
